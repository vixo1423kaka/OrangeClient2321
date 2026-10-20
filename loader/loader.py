import ctypes
import http.server
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import threading
import tkinter as tk
import urllib.parse
import urllib.request
import webbrowser
from pathlib import Path
from tkinter import messagebox

# --- Ukrywanie okien cmd na Windows (Java, instalator Fabric itp.) -------------------------
if os.name == "nt":
    _orig_popen_init = subprocess.Popen.__init__

    def _quiet_popen_init(self, *args, **kwargs):
        kwargs["creationflags"] = kwargs.get("creationflags", 0) | 0x08000000  # CREATE_NO_WINDOW
        si = kwargs.get("startupinfo") or subprocess.STARTUPINFO()
        si.dwFlags |= subprocess.STARTF_USESHOWWINDOW
        si.wShowWindow = 0
        kwargs["startupinfo"] = si
        _orig_popen_init(self, *args, **kwargs)

    subprocess.Popen.__init__ = _quiet_popen_init

try:
    import customtkinter as ctk
    import minecraft_launcher_lib as mll
except ImportError:
    ctk = None
    mll = None

APPDATA = Path(os.environ.get("APPDATA", str(Path.home())))
DATA_DIR = APPDATA / "OrangeClientLoader"
SETTINGS_FILE = DATA_DIR / "settings.json"
INSTANCES = DATA_DIR / "instances"
MOD_JAR = "orange-client-0.1.0.jar"
SUPPORTED_VERSIONS = [f"1.21.{n}" for n in range(4, 12)]
# Orange Client JAR w repo jest obecnie kompilowany dla 1.21.4; inne wersje wymagają portu moda.
# Microsoft OAuth jest opcjonalny. Build nie wymaga Client ID: bez niego działa tryb offline.
# Aby włączyć logowanie Microsoft, ustaw ORANGE_AZURE_CLIENT_ID albo wpisz Client ID
# własnej aplikacji w Ustawieniach. Można też opcjonalnie dołączyć _build_config.py.
try:
    from _build_config import AZURE_CLIENT_ID  # type: ignore[import-not-found]
except ImportError:
    AZURE_CLIENT_ID = ""
REDIRECT_PORT = 28487
REDIRECT_URI = f"http://localhost:{REDIRECT_PORT}"

BG, SIDE, PANEL, BORDER = "#0b0d12", "#10141c", "#151b26", "#242c3b"
TEXT, MUTED, ORANGE, ORANGE_H = "#f2f4f8", "#8691a6", "#f47c20", "#ff963f"
GREEN, RED = "#69d69a", "#ff6b6b"


# --- Ochrona sekretów (DPAPI na Windows, w innym wypadku zwykły tekst) ----------------------
class _Blob(ctypes.Structure):
    _fields_ = [("cbData", ctypes.c_uint32), ("pbData", ctypes.POINTER(ctypes.c_char))]


def _dpapi(data: bytes, protect: bool) -> bytes:
    inp = _Blob(len(data), ctypes.cast(ctypes.create_string_buffer(data, len(data)), ctypes.POINTER(ctypes.c_char)))
    out = _Blob()
    fn = ctypes.windll.crypt32.CryptProtectData if protect else ctypes.windll.crypt32.CryptUnprotectData
    if not fn(ctypes.byref(inp), None, None, None, None, 0, ctypes.byref(out)):
        raise OSError("DPAPI")
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(out.pbData)


def seal(text: str) -> str:
    if os.name == "nt" and text:
        try:
            return "dpapi:" + _dpapi(text.encode(), True).hex()
        except OSError:
            pass
    return "plain:" + text


def unseal(text: str) -> str:
    try:
        if text.startswith("dpapi:"):
            return _dpapi(bytes.fromhex(text[6:]), False).decode()
        if text.startswith("plain:"):
            return text[6:]
    except (OSError, ValueError):
        pass
    return ""


def load_settings() -> dict:
    try:
        return json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def save_settings(data: dict) -> None:
    try:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        SETTINGS_FILE.write_text(json.dumps(data, indent=2), encoding="utf-8")
    except OSError:
        pass



# --- Wykrywanie kont w innych launcherach (TYLKO nick/UUID/e-mail, nigdy tokeny) -------------
def detect_external_accounts():
    found = []

    def add(source, name, uid, hint=""):
        uid = (uid or "").replace("-", "").lower()
        if name and re.fullmatch(r"[0-9a-f]{32}", uid) and not any(a["id"] == uid for a in found):
            found.append({"source": source, "name": name, "id": uid, "hint": hint if "@" in (hint or "") else ""})

    try:  # oficjalny launcher
        data = json.loads((APPDATA / ".minecraft" / "launcher_accounts.json").read_text(encoding="utf-8"))
        for acc in (data.get("accounts") or {}).values():
            prof = acc.get("minecraftProfile") or {}
            add("Launcher Minecraft", prof.get("name"), prof.get("id"), acc.get("username", ""))
    except (OSError, ValueError, AttributeError):
        pass
    for label, folder in (("Prism", "PrismLauncher"), ("MultiMC", "MultiMC")):
        try:
            data = json.loads((APPDATA / folder / "accounts.json").read_text(encoding="utf-8"))
            for acc in data.get("accounts", []):
                prof = acc.get("profile") or {}
                add(label, prof.get("name"), prof.get("id"))
        except (OSError, ValueError, AttributeError):
            pass
    for folder in ("ModrinthApp", "com.modrinth.theseus"):  # Modrinth App (baza SQLite, tylko odczyt)
        db = APPDATA / folder / "app.db"
        if not db.is_file():
            continue
        try:
            con = sqlite3.connect(f"file:{db.as_posix()}?mode=ro", uri=True, timeout=2)
            try:
                cols = {r[1] for r in con.execute("PRAGMA table_info(minecraft_users)")}
                if {"uuid", "username"} <= cols:
                    for uid, name in con.execute("SELECT uuid, username FROM minecraft_users"):
                        add("Modrinth", name, str(uid))
            finally:
                con.close()
        except (sqlite3.Error, OSError):
            pass
    return found

# --- Logowanie Microsoft (własne OAuth, PKCE, przekierowanie na localhost) ----------------
def microsoft_login(client_id: str, hint: str = "", timeout: int = 180) -> dict:
    """Otwiera przeglądarkę, czeka na kod i zwraca dane konta (id, name, access_token, refresh_token)."""
    url, state, verifier = mll.microsoft_account.get_secure_login_data(client_id, REDIRECT_URI)
    if hint:
        url += "&" + urllib.parse.urlencode({"login_hint": hint})
    result = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            result["url"] = f"{REDIRECT_URI}{self.path}"
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write("<html><body style='font-family:Segoe UI;background:#0b0d12;color:#fff;"
                             "text-align:center;padding-top:80px'><h2>Zalogowano w Orange Client</h2>"
                             "<p>Możesz zamknąć tę kartę i wrócić do loadera.</p></body></html>".encode("utf-8"))

        def log_message(self, *args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", REDIRECT_PORT), Handler)
    server.timeout = timeout
    try:
        webbrowser.open(url)
        server.handle_request()
    finally:
        server.server_close()
    if "url" not in result:
        raise RuntimeError("Przekroczono czas logowania.")
    code = mll.microsoft_account.parse_auth_code_url(result["url"], state)
    return mll.microsoft_account.complete_login(client_id, None, REDIRECT_URI, code, verifier)


def humanize_login_error(exc: Exception) -> str:
    name = type(exc).__name__
    if name == "AccountNotOwnMinecraft":
        return "To konto Microsoft nie posiada Minecrafta Java Edition."
    if name == "AzureAppNotPermitted":
        return "Aplikacja Azure nie ma zgody na Minecraft API (patrz README, sekcja logowanie)."
    return str(exc) or name


class Loader(ctk.CTk if ctk else object):
    def __init__(self):
        ctk.set_appearance_mode("dark")
        ctk.set_default_color_theme("dark-blue")
        super().__init__()
        self.title("Orange Client")
        self.geometry("920x640")
        self.minsize(820, 560)
        self._borderless = os.name == "nt"
        self._maximized = False
        self._normal_geometry = "920x640"
        self._drag_origin = None
        # On Windows, use a color-keyed transparent desktop window so only the
        # rounded app surface is visible outside its client area.
        if self._borderless:
            self.configure(fg_color="#ff00ff")
            try:
                self.attributes("-transparentcolor", "#ff00ff")
                self.overrideredirect(True)
            except tk.TclError:
                self._borderless = False
                self.configure(fg_color=BG)
        else:
            self.configure(fg_color=BG)
        icon_path = Path(getattr(sys, "_MEIPASS", Path(__file__).parent)) / "orange.ico"
        try:
            if icon_path.is_file():
                self.iconbitmap(str(icon_path))
        except (OSError, tk.TclError):
            pass

        self.settings = load_settings()
        s = self.settings
        self.accounts = []  # zapisane konta: {"id","name","refresh"}
        for a in s.get("accounts", []):
            if a.get("refresh"):
                self.accounts.append({"id": a.get("id", ""), "name": a.get("name", ""), "refresh": unseal(a["refresh"])})
        self.active_id = s.get("active_account", "")
        self.detected = detect_external_accounts()
        self.choice = None  # aktualnie wybrana pozycja z listy
        self.mode = tk.StringVar(value=s.get("mode", "offline"))
        self.username = tk.StringVar(value=s.get("username", "Player"))
        self.license_key = tk.StringVar(value=s.get("license_key", "") or self._key_from_instances())
        self.license_key.trace_add("write", lambda *_: self._persist())
        self.username.trace_add("write", lambda *_: self._persist())
        self._busy = False

        self._build_ui()
        self._show_play()
        self._refresh_account_label()
        if self.accounts:
            threading.Thread(target=self._silent_refresh, daemon=True).start()

    # ---- stan -------------------------------------------------------------------------------
    @staticmethod
    def _key_from_instances():
        for version in SUPPORTED_VERSIONS:
            f = INSTANCES / version / "config" / "orangeclient_license.json"
            try:
                key = json.loads(f.read_text(encoding="utf-8")).get("key", "")
                if key:
                    return key
            except (OSError, ValueError):
                pass
        return ""

    def _client_id(self):
        return (os.environ.get("ORANGE_AZURE_CLIENT_ID") or AZURE_CLIENT_ID.strip()
                or self.settings.get("azure_client_id") or "").strip()

    def _persist(self):
        self.settings["license_key"] = self.license_key.get().strip()
        self.settings["username"] = self.username.get().strip()
        self.settings["mode"] = self.mode.get()
        self.settings.pop("account", None)
        self.settings["accounts"] = [{"id": a["id"], "name": a["name"], "refresh": seal(a["refresh"])} for a in self.accounts]
        self.settings["active_account"] = self.active_id
        save_settings(self.settings)

    def ui(self, fn):
        self.after(0, fn)

    # ---- UI ---------------------------------------------------------------------------------
    def _build_ui(self):
        # Rounded application shell sits over a transparent color-key background.
        self.shell = ctk.CTkFrame(self, fg_color=BG, corner_radius=24,
                                  border_width=1, border_color=BORDER)
        self.shell.pack(fill="both", expand=True, padx=2, pady=2)

        # macOS-inspired title bar and three traffic-light controls.
        titlebar = ctk.CTkFrame(self.shell, fg_color=SIDE, corner_radius=20, height=42)
        titlebar.pack(fill="x", padx=2, pady=(2, 0))
        titlebar.pack_propagate(False)
        lights = ctk.CTkFrame(titlebar, fg_color="transparent")
        lights.pack(side="left", padx=(14, 10), pady=10)
        for color, hover, command in (
            ("#ff5f57", "#ff817b", self._window_close),
            ("#febc2e", "#ffd166", self._window_minimize),
            ("#28c840", "#55df68", self._window_toggle_maximize),
        ):
            ctk.CTkButton(lights, text="", width=13, height=13, corner_radius=8,
                          fg_color=color, hover_color=hover, border_width=0,
                          command=command).pack(side="left", padx=(0, 7))
        title = ctk.CTkLabel(titlebar, text="Orange Client", text_color="#c5cad4",
                             font=("Segoe UI", 12, "bold"))
        title.pack(side="left", padx=4)
        for widget in (titlebar, title):
            widget.bind("<ButtonPress-1>", self._drag_start)
            widget.bind("<B1-Motion>", self._drag_motion)

        content = ctk.CTkFrame(self.shell, fg_color="transparent", corner_radius=20)
        content.pack(side="top", fill="both", expand=True, padx=2, pady=(2, 2))
        side = ctk.CTkFrame(content, width=170, corner_radius=18, fg_color=SIDE)
        side.pack(side="left", fill="y", padx=(0, 2))
        side.pack_propagate(False)
        ctk.CTkLabel(side, text="ORANGE", font=("Segoe UI", 20, "bold"), text_color=ORANGE).pack(anchor="w", padx=20, pady=(26, 0))
        ctk.CTkLabel(side, text="CLIENT", font=("Segoe UI", 11, "bold"), text_color=MUTED).pack(anchor="w", padx=20, pady=(0, 28))
        self.nav = {}
        for key, label, cmd in (("play", "Graj", self._show_play), ("mods", "Mody", self._show_mods), ("settings", "Ustawienia", self._show_settings)):
            b = ctk.CTkButton(side, text=label, anchor="w", height=38, corner_radius=8,
                              fg_color="transparent", hover_color="#1b2230", text_color=MUTED,
                              font=("Segoe UI", 13), command=cmd)
            b.pack(fill="x", padx=10, pady=2)
            self.nav[key] = b

        main = ctk.CTkFrame(content, fg_color="transparent", corner_radius=18)
        main.pack(side="left", fill="both", expand=True)
        self.page = ctk.CTkFrame(main, fg_color="transparent", corner_radius=18)
        self.page.pack(fill="both", expand=True, padx=28, pady=(20, 8))

        dock = ctk.CTkFrame(main, fg_color=SIDE, corner_radius=16)
        dock.pack(side="bottom", fill="x", padx=2, pady=2)
        inner = ctk.CTkFrame(dock, fg_color="transparent")
        inner.pack(fill="x", padx=24, pady=12)
        self.play_btn = ctk.CTkButton(inner, text="▶  GRAJ", width=190, height=48, corner_radius=14,
                                      font=("Segoe UI", 15, "bold"), fg_color=ORANGE, hover_color=ORANGE_H,
                                      text_color="#101116", command=self.play)
        self.play_btn.pack(side="right")
        self.status = ctk.CTkLabel(inner, text="Gotowy.", font=("Segoe UI", 12), text_color=MUTED,
                                   anchor="w", justify="left", wraplength=420)
        self.status.pack(side="left", fill="x", expand=True)

    def _drag_start(self, event):
        if self._maximized:
            return
        self._drag_origin = (event.x_root - self.winfo_x(), event.y_root - self.winfo_y())

    def _drag_motion(self, event):
        if self._drag_origin and not self._maximized:
            x = event.x_root - self._drag_origin[0]
            y = event.y_root - self._drag_origin[1]
            self.geometry(f"+{x}+{y}")

    def _window_close(self):
        self.destroy()

    def _window_minimize(self):
        if self._borderless:
            self.overrideredirect(False)
            self.iconify()
            self.bind("<Map>", self._restore_borderless, add="+")
        else:
            self.iconify()

    def _restore_borderless(self, _event=None):
        if self._borderless:
            self.after(120, lambda: self.overrideredirect(True) if self.winfo_exists() else None)

    def _window_toggle_maximize(self):
        if not self._maximized:
            self._normal_geometry = self.geometry()
            sw, sh = self.winfo_screenwidth(), self.winfo_screenheight()
            self.geometry(f"{sw - 24}x{sh - 64}+12+12")
            self._maximized = True
        else:
            self.geometry(self._normal_geometry)
            self._maximized = False

    def _card(self, title):
        card = ctk.CTkFrame(self.page, fg_color=PANEL, corner_radius=12, border_width=1, border_color=BORDER)
        card.pack(fill="x", pady=6)
        ctk.CTkLabel(card, text=title, font=("Segoe UI", 11, "bold"), text_color=ORANGE).pack(anchor="w", padx=16, pady=(12, 6))
        return card

    def _set_nav(self, active):
        for k, b in self.nav.items():
            b.configure(fg_color="#231d1a" if k == active else "transparent",
                        text_color=ORANGE if k == active else MUTED)

    def _clear(self):
        for w in self.page.winfo_children():
            w.destroy()

    def _show_play(self):
        self._set_nav("play")
        self._clear()
        ctk.CTkLabel(self.page, text="Graj", font=("Segoe UI", 26, "bold"), text_color=TEXT).pack(anchor="w", pady=(0, 8))

        c = self._card("WERSJA")
        self.version_box = ctk.CTkComboBox(c, values=SUPPORTED_VERSIONS, state="readonly", height=36,
                                           border_color=BORDER, button_color="#2b3446",
                                           command=self._on_version_changed)
        self.version_box.set(self.settings.get("version", SUPPORTED_VERSIONS[0]) if self.settings.get("version") in SUPPORTED_VERSIONS else SUPPORTED_VERSIONS[0])
        self.version_box.pack(fill="x", padx=16, pady=(0, 4))
        ctk.CTkLabel(c, text="Orange Client jest obecnie dostępny dla 1.21.4. Pozostałe wersje uruchamiają Fabric i zgodne mody.",
                     text_color=MUTED, font=("Segoe UI", 10), wraplength=620, justify="left").pack(anchor="w", padx=16, pady=(0, 12))

        c = self._card("KONTO")
        seg = ctk.CTkSegmentedButton(c, values=["Microsoft", "Offline"], command=self._on_mode, height=34,
                                     selected_color="#e8751e", selected_hover_color=ORANGE_H,
                                     unselected_color="#1f2736", unselected_hover_color="#2a3447")
        seg.set("Microsoft" if self.mode.get() == "microsoft" else "Offline")
        seg.pack(fill="x", padx=16)
        self.account_row = ctk.CTkFrame(c, fg_color="transparent")
        self.account_row.pack(fill="x", padx=16, pady=(10, 14))
        self.offline_entry = ctk.CTkEntry(self.account_row, textvariable=self.username, height=34,
                                          placeholder_text="Nazwa gracza (3-16 znaków)", border_color=BORDER)
        self.account_box = ctk.CTkComboBox(self.account_row, values=[""], state="readonly", height=34,
                                           border_color=BORDER, button_color="#2b3446",
                                           command=self._on_account_pick)
        self.login_btn = ctk.CTkButton(self.account_row, text="Zaloguj", width=90, height=34,
                                       fg_color=ORANGE, hover_color=ORANGE_H, text_color="#101116",
                                       command=self._login_clicked)
        self.logout_btn = ctk.CTkButton(self.account_row, text="Wyloguj", width=90, height=34,
                                        fg_color="#2b3446", hover_color="#38445b", command=self._logout)
        self.more_btn = ctk.CTkButton(self.account_row, text="+ Inne konto", width=110, height=34,
                                      fg_color="#2b3446", hover_color="#38445b",
                                      command=lambda: self._login_clicked(other=True))
        self._layout_account_row()

        c = self._card("KLUCZ LICENCJI")
        row = ctk.CTkFrame(c, fg_color="transparent")
        row.pack(fill="x", padx=16, pady=(0, 14))
        self.key_entry = ctk.CTkEntry(row, textvariable=self.license_key, height=36, show="•",
                                      placeholder_text="Wklej klucz (zapisze się automatycznie)", border_color=BORDER)
        self.key_entry.pack(side="left", fill="x", expand=True)
        self.show_key = ctk.CTkCheckBox(row, text="Pokaż", width=70, command=self._toggle_key)
        self.show_key.pack(side="left", padx=(12, 0))

    def _on_version_changed(self, value):
        self.settings["version"] = value
        self._persist()

    def _toggle_key(self):
        self.key_entry.configure(show="" if self.show_key.get() else "•")


    def _show_mods(self):
        self._set_nav("mods")
        self._clear()
        ctk.CTkLabel(self.page, text="Mody", font=("Segoe UI", 26, "bold"), text_color=TEXT).pack(anchor="w", pady=(0, 4))
        ctk.CTkLabel(self.page, text="Wyszukuj i instaluj mody Fabric z Modrinth do wybranej instancji.",
                     font=("Segoe UI", 11), text_color=MUTED).pack(anchor="w", pady=(0, 12))
        row = ctk.CTkFrame(self.page, fg_color="transparent")
        row.pack(fill="x", pady=(0, 10))
        self.mod_query = tk.StringVar()
        entry = ctk.CTkEntry(row, textvariable=self.mod_query, height=38, placeholder_text="Szukaj modów (np. Sodium, Lithium)…",
                             border_color=BORDER)
        entry.pack(side="left", fill="x", expand=True)
        entry.bind("<Return>", lambda _e: self._search_mods())
        ctk.CTkButton(row, text="Szukaj", width=95, height=38, fg_color=ORANGE, hover_color=ORANGE_H,
                      text_color="#101116", command=self._search_mods).pack(side="left", padx=(8, 0))
        ctk.CTkLabel(self.page, text="Zainstalowane mody — wersja " + self._selected_version(),
                     font=("Segoe UI", 13, "bold"), text_color=TEXT).pack(anchor="w", pady=(0, 6))
        self.installed_frame = ctk.CTkScrollableFrame(self.page, fg_color=PANEL, corner_radius=10,
                                                       border_width=1, border_color=BORDER, height=120)
        self.installed_frame.pack(fill="x", pady=(0, 12))
        self._refresh_installed_mods()
        ctk.CTkLabel(self.page, text="Wyniki Modrinth", font=("Segoe UI", 13, "bold"),
                     text_color=TEXT).pack(anchor="w", pady=(0, 6))
        self.mod_results = ctk.CTkScrollableFrame(self.page, fg_color=BG, corner_radius=10, height=220)
        self.mod_results.pack(fill="both", expand=True)
        ctk.CTkLabel(self.mod_results, text="Wpisz nazwę moda i kliknij Szukaj.",
                     text_color=MUTED, font=("Segoe UI", 11)).pack(anchor="w", padx=10, pady=12)

    def _selected_version(self):
        if hasattr(self, "version_box") and self.version_box.winfo_exists():
            try:
                return self.version_box.get().strip()
            except Exception:
                pass
        return self.settings.get("version", SUPPORTED_VERSIONS[0])

    def _modrinth_json(self, url):
        req = urllib.request.Request(url, headers={"User-Agent": "OrangeClientLoader/1.2 (OrangeClient2321)"})
        with urllib.request.urlopen(req, timeout=20) as response:
            return json.loads(response.read().decode("utf-8"))

    def _search_mods(self):
        query = self.mod_query.get().strip()
        if not query:
            return
        for widget in self.mod_results.winfo_children():
            widget.destroy()
        ctk.CTkLabel(self.mod_results, text="Szukam w Modrinth…", text_color=MUTED).pack(anchor="w", padx=10, pady=10)
        threading.Thread(target=self._search_mods_worker, args=(query, self._selected_version()), daemon=True).start()

    def _search_mods_worker(self, query, version):
        try:
            facets = json.dumps([["project_type:mod"], ["categories:fabric"]], separators=(",", ":"))
            url = "https://api.modrinth.com/v2/search?" + urllib.parse.urlencode(
                {"query": query, "facets": facets, "limit": 15, "index": "relevance"})
            results = self._modrinth_json(url).get("hits", [])
            self.ui(lambda: self._render_mod_results(results, version))
        except Exception as exc:
            msg = str(exc)
            self.ui(lambda: self._show_mod_search_error(msg))

    def _show_mod_search_error(self, message):
        if not hasattr(self, "mod_results") or not self.mod_results.winfo_exists():
            return
        for widget in self.mod_results.winfo_children():
            widget.destroy()
        ctk.CTkLabel(self.mod_results, text="Nie udało się wyszukać modów: " + message,
                     text_color=RED, wraplength=500).pack(anchor="w", padx=10, pady=10)

    def _render_mod_results(self, results, version):
        if not hasattr(self, "mod_results") or not self.mod_results.winfo_exists():
            return
        for widget in self.mod_results.winfo_children():
            widget.destroy()
        if not results:
            ctk.CTkLabel(self.mod_results, text="Brak wyników.", text_color=MUTED).pack(anchor="w", padx=10, pady=10)
            return
        for item in results:
            card = ctk.CTkFrame(self.mod_results, fg_color=PANEL, corner_radius=8)
            card.pack(fill="x", padx=3, pady=4)
            details = ctk.CTkFrame(card, fg_color="transparent")
            details.pack(side="left", fill="x", expand=True, padx=10, pady=8)
            ctk.CTkLabel(details, text=item.get("title", item.get("slug", "Mod")),
                         font=("Segoe UI", 12, "bold"), text_color=TEXT).pack(anchor="w")
            desc = (item.get("description") or "").replace("\\n", " ")
            ctk.CTkLabel(details, text=desc[:150], text_color=MUTED, font=("Segoe UI", 10),
                         wraplength=340, justify="left").pack(anchor="w")
            ctk.CTkButton(card, text="Zainstaluj", width=100, height=32,
                          fg_color=ORANGE, hover_color=ORANGE_H, text_color="#101116",
                          command=lambda mod=item, ver=version: self._install_mod_clicked(mod, ver)).pack(side="right", padx=10, pady=8)

    def _install_mod_clicked(self, item, version):
        if self._busy:
            messagebox.showinfo("Instalacja", "Poczekaj, aż zakończy się uruchamianie gry.")
            return
        threading.Thread(target=self._install_mod_worker, args=(item, version), daemon=True).start()

    def _install_mod_worker(self, item, version):
        try:
            self._set_status("Pobieranie moda " + item.get("title", item.get("slug", "")) + "…")
            params = urllib.parse.urlencode({
                "game_versions": json.dumps([version]),
                "loaders": json.dumps(["fabric"]),
            })
            versions = self._modrinth_json("https://api.modrinth.com/v2/project/" +
                                           urllib.parse.quote(item["project_id"], safe="") + "/version?" + params)
            compatible = [v for v in versions if v.get("version_type") == "release"] or versions
            if not compatible:
                raise RuntimeError("Ten mod nie ma wydania dla Minecraft " + version + " i Fabric.")
            files = compatible[0].get("files", [])
            chosen = next((f for f in files if f.get("primary")), files[0] if files else None)
            if not chosen or not chosen.get("url") or not chosen.get("filename", "").lower().endswith(".jar"):
                raise RuntimeError("Modrinth nie zwrócił pliku JAR dla tej wersji.")
            mods_dir = INSTANCES / version / "mods"
            mods_dir.mkdir(parents=True, exist_ok=True)
            target = mods_dir / Path(chosen["filename"]).name
            request = urllib.request.Request(chosen["url"], headers={"User-Agent": "OrangeClientLoader/1.2"})
            with urllib.request.urlopen(request, timeout=60) as response, target.open("wb") as output:
                shutil.copyfileobj(response, output)
            self._set_status("Zainstalowano: " + target.name)
            self.ui(lambda: self._refresh_installed_mods())
        except Exception as exc:
            msg = str(exc)
            self.ui(lambda: messagebox.showerror("Nie udało się zainstalować moda", msg))
            self._set_status("Instalacja moda nie powiodła się.")

    def _refresh_installed_mods(self):
        if not hasattr(self, "installed_frame") or not self.installed_frame.winfo_exists():
            return
        for widget in self.installed_frame.winfo_children():
            widget.destroy()
        mods_dir = INSTANCES / self._selected_version() / "mods"
        files = sorted(mods_dir.glob("*.jar")) if mods_dir.exists() else []
        if not files:
            ctk.CTkLabel(self.installed_frame, text="Brak dodatkowych modów w tej instancji.",
                         text_color=MUTED, font=("Segoe UI", 11)).pack(anchor="w", padx=10, pady=8)
            return
        for file in files:
            row = ctk.CTkFrame(self.installed_frame, fg_color="transparent")
            row.pack(fill="x", padx=4, pady=2)
            ctk.CTkLabel(row, text=file.name, text_color=TEXT, font=("Segoe UI", 11),
                         anchor="w").pack(side="left", fill="x", expand=True, padx=6, pady=4)
            protected = file.name.lower().startswith(("orange-client", "fabric-api"))
            if protected:
                ctk.CTkLabel(row, text="Wymagany", text_color=MUTED, font=("Segoe UI", 10)).pack(side="right", padx=8)
            else:
                ctk.CTkButton(row, text="Usuń", width=60, height=28, fg_color="#2b3446",
                              hover_color="#38445b", command=lambda p=file: self._remove_mod(p)).pack(side="right", padx=4)

    def _remove_mod(self, path):
        try:
            path.unlink(missing_ok=True)
            self._refresh_installed_mods()
            self.status.configure(text="Usunięto mod: " + path.name)
        except OSError as exc:
            messagebox.showerror("Nie udało się usunąć moda", str(exc))

    def _show_settings(self):
        self._set_nav("settings")
        self._clear()
        ctk.CTkLabel(self.page, text="Ustawienia", font=("Segoe UI", 26, "bold"), text_color=TEXT).pack(anchor="w", pady=(0, 8))
        c = self._card("FOLDER GRY")
        ctk.CTkLabel(c, text=str(INSTANCES), text_color=MUTED, font=("Segoe UI", 11), anchor="w").pack(fill="x", padx=16)
        ctk.CTkButton(c, text="Otwórz folder", width=130, height=32, fg_color="#2b3446", hover_color="#38445b",
                      command=self._open_dir).pack(anchor="w", padx=16, pady=(8, 14))
        if not (AZURE_CLIENT_ID.strip() or os.environ.get("ORANGE_AZURE_CLIENT_ID")):
            # Pole pokazujemy tylko, gdy Client ID nie jest wbudowany w loader.
            c = self._card("AZURE CLIENT ID (logowanie Microsoft)")
            self.cid_var = tk.StringVar(value=self._client_id())
            ctk.CTkEntry(c, textvariable=self.cid_var, height=34, border_color=BORDER,
                         placeholder_text="np. 00000000-0000-0000-0000-000000000000").pack(fill="x", padx=16)
            ctk.CTkButton(c, text="Zapisz", width=90, height=32, fg_color="#2b3446", hover_color="#38445b",
                          command=self._save_cid).pack(anchor="w", padx=16, pady=(8, 14))

    def _open_dir(self):
        INSTANCES.mkdir(parents=True, exist_ok=True)
        try:
            os.startfile(str(INSTANCES))  # type: ignore[attr-defined]
        except AttributeError:
            webbrowser.open(INSTANCES.as_uri())

    def _save_cid(self):
        self.settings["azure_client_id"] = self.cid_var.get().strip()
        save_settings(self.settings)
        self.status.configure(text="Zapisano Client ID.")

    # ---- konto ------------------------------------------------------------------------------
    @property
    def account(self):
        return next((a for a in self.accounts if a["id"] == self.active_id), None)

    def _choices(self):
        out = [{"label": f"{a['name']}  ✓", "saved": True, "id": a["id"], "hint": ""} for a in self.accounts]
        saved = {a["id"] for a in self.accounts}
        for d in self.detected:
            if d["id"] not in saved:
                out.append({"label": f"{d['name']}  · {d['source']} (kliknij Zaloguj)", "saved": False,
                            "id": d["id"], "hint": d["hint"]})
        return out

    def _on_mode(self, value):
        self.mode.set("microsoft" if value == "Microsoft" else "offline")
        self._persist()
        self._layout_account_row()

    def _layout_account_row(self):
        for w in (self.offline_entry, self.account_box, self.login_btn, self.logout_btn, self.more_btn):
            w.pack_forget()
        if self.mode.get() == "offline":
            self.offline_entry.pack(fill="x")
            return
        choices = self._choices()
        if not choices:
            self.choice = None
            self.account_box.configure(values=["Brak kont – zaloguj się"])
            self.account_box.set("Brak kont – zaloguj się")
        else:
            current = next((c for c in choices if c["id"] == self.active_id), choices[0])
            self.choice = current
            self.account_box.configure(values=[c["label"] for c in choices])
            self.account_box.set(current["label"])
            if current["saved"]:
                self.active_id = current["id"]
        self.account_box.pack(side="left", fill="x", expand=True)
        self.more_btn.pack(side="right", padx=(8, 0))
        if self.choice and self.choice["saved"]:
            self.logout_btn.pack(side="right", padx=(8, 0))
        else:
            self.login_btn.pack(side="right", padx=(8, 0))

    def _on_account_pick(self, label):
        for c in self._choices():
            if c["label"] == label:
                self.choice = c
                if c["saved"]:
                    self.active_id = c["id"]
                    self._persist()
        self._layout_account_row()

    def _refresh_account_label(self):
        if hasattr(self, "account_row") and self.account_row.winfo_exists():
            self._layout_account_row()

    def _login_clicked(self, other=False):
        cid = self._client_id()
        if not cid:
            messagebox.showinfo("Logowanie Microsoft",
                                "Brak Azure Client ID. Wklej go w Ustawieniach (instrukcja w README loadera).")
            self._show_settings()
            return
        hint = "" if (other or not self.choice) else self.choice.get("hint", "")
        self.login_btn.configure(state="disabled")
        self.more_btn.configure(state="disabled")
        self.status.configure(text="Dokończ logowanie w przeglądarce.")
        threading.Thread(target=self._login_worker, args=(cid, hint), daemon=True).start()

    def _login_worker(self, cid, hint):
        try:
            data = microsoft_login(cid, hint)
            entry = {"id": data["id"].replace("-", "").lower(), "name": data["name"], "refresh": data["refresh_token"]}
            self.accounts = [a for a in self.accounts if a["id"] != entry["id"]] + [entry]
            self.active_id = entry["id"]
            self._persist()
            self._set_status(f"Zalogowano jako {data['name']}.")
        except Exception as exc:
            msg = humanize_login_error(exc)
            self.ui(lambda: messagebox.showerror("Logowanie nie powiodło się", msg))
        finally:
            self.ui(lambda: (self.login_btn.configure(state="normal"), self.more_btn.configure(state="normal"),
                             self._refresh_account_label()))

    def _logout(self):
        self.accounts = [a for a in self.accounts if a["id"] != self.active_id]
        self.active_id = self.accounts[0]["id"] if self.accounts else ""
        self._persist()
        self._layout_account_row()
        self.status.configure(text="Wylogowano.")

    def _silent_refresh(self):
        """Przy starcie odświeża zapisane sesje, żeby konta nadal były oznaczone jako zalogowane."""
        cid = self._client_id()
        if not cid:
            return
        for acc in list(self.accounts):
            try:
                data = mll.microsoft_account.complete_refresh(cid, None, None, acc["refresh"])
                acc["refresh"], acc["name"] = data["refresh_token"], data["name"]
            except Exception:
                pass
        self._persist()
        self.ui(self._refresh_account_label)

    # ---- uruchamianie -----------------------------------------------------------------------
    def play(self):
        if self._busy:
            return
        version = self.version_box.get().strip()
        key = self.license_key.get().strip()
        if not key:
            messagebox.showerror("Brak licencji", "Wpisz klucz licencji Orange Client.")
            return
        microsoft = self.mode.get() == "microsoft"
        username = self.username.get().strip()
        if microsoft:
            if not self.account:
                messagebox.showinfo("Konto", "Najpierw zaloguj się przez Microsoft albo wybierz tryb Offline.")
                return
        elif not re.fullmatch(r"[A-Za-z0-9_]{3,16}", username):
            messagebox.showerror("Nieprawidłowa nazwa", "Nazwa offline: 3–16 znaków, litery, cyfry lub _.")
            return
        self.settings["version"] = version
        self._persist()
        self._busy = True
        self.play_btn.configure(state="disabled", text="PRZYGOTOWANIE…")
        threading.Thread(target=self._launch, args=(version, key, microsoft, username), daemon=True).start()

    def _set_status(self, text):
        self.ui(lambda: self.status.configure(text=text))

    def _launch(self, version, key, microsoft, username):
        try:
            game_dir = INSTANCES / version
            (game_dir / "config").mkdir(parents=True, exist_ok=True)
            (game_dir / "config" / "orangeclient_license.json").write_text(json.dumps({"key": key}), encoding="utf-8")

            options = {"gameDirectory": str(game_dir)}
            if microsoft:
                self._set_status("Odświeżanie sesji Microsoft…")
                cid = self._client_id()
                if not cid:
                    raise RuntimeError("Brak Azure Client ID (Ustawienia).")
                data = mll.microsoft_account.complete_refresh(cid, None, None, self.account["refresh"])
                acc = self.account
                acc["refresh"], acc["name"] = data["refresh_token"], data["name"]
                self._persist()
                options.update({"username": data["name"], "uuid": data["id"], "token": data["access_token"]})
            else:
                options.update({"username": username, "uuid": "00000000-0000-0000-0000-000000000000", "token": ""})

            cb = {"setStatus": lambda t: self._set_status(t)}
            self._set_status("Sprawdzanie plików gry…")
            mll.install.install_minecraft_version(version, str(game_dir), callback=cb)

            fabric_id = next((v["id"] for v in mll.utils.get_installed_versions(str(game_dir))
                              if v["id"].startswith("fabric-loader-") and v["id"].endswith("-" + version)), None)
            if not fabric_id:
                self._set_status("Instalacja Fabric…")
                java = None
                try:
                    vjson = json.loads((game_dir / "versions" / version / f"{version}.json").read_text(encoding="utf-8"))
                    comp = vjson.get("javaVersion", {}).get("component")
                    java = mll.runtime.get_executable_path(comp, str(game_dir)) if comp else None
                except (OSError, ValueError):
                    pass
                mll.fabric.install_fabric(version, str(game_dir), callback=cb, java=java)
                fabric_id = next(v["id"] for v in mll.utils.get_installed_versions(str(game_dir))
                                 if v["id"].startswith("fabric-loader-") and v["id"].endswith("-" + version))

            mods = game_dir / "mods"
            mods.mkdir(exist_ok=True)
            bundled = Path(getattr(sys, "_MEIPASS", Path(__file__).parent)) / "client-mods" / MOD_JAR
            if version == "1.21.4":
                if not bundled.exists():
                    raise RuntimeError("Brak wbudowanego pliku moda Orange Client w loaderze.")
                shutil.copy2(bundled, mods / MOD_JAR)
            else:
                self._set_status("Fabric dla tej wersji zostanie uruchomiony bez Orange Client — mod wymaga osobnego portu.")
            if not any(p.name.startswith("fabric-api") for p in mods.glob("*.jar")):
                self._set_status("Pobieranie Fabric API…")
                self._download_fabric_api(version, mods)

            self._set_status("Uruchamianie gry…")
            command = mll.command.get_minecraft_command(fabric_id, str(game_dir), options)
            if os.name == "nt" and command and command[0].lower().endswith("java.exe"):
                javaw = command[0][:-8] + "javaw.exe"
                if os.path.exists(javaw):
                    command[0] = javaw
            subprocess.Popen(command, cwd=str(game_dir))
            self._set_status("Gra uruchomiona. Licencja jest sprawdzana po starcie klienta.")
        except Exception as exc:
            error = humanize_login_error(exc)
            self.ui(lambda: messagebox.showerror("Nie udało się uruchomić", error))
            self._set_status("Uruchomienie nie powiodło się.")
        finally:
            self.ui(lambda: (setattr(self, "_busy", False), self.play_btn.configure(state="normal", text="▶  GRAJ")))

    @staticmethod
    def _download_fabric_api(version, mods):
        api = "https://api.modrinth.com/v2/project/fabric-api/version?" + urllib.parse.urlencode(
            {"game_versions": json.dumps([version]), "loaders": json.dumps(["fabric"])})
        req = urllib.request.Request(api, headers={"User-Agent": "OrangeClientLoader/1.1"})
        with urllib.request.urlopen(req, timeout=20) as r:
            versions = json.loads(r.read().decode("utf-8"))
        if not versions:
            raise RuntimeError("Nie znaleziono Fabric API dla tej wersji.")
        files = versions[0].get("files", [])
        info = next((f for f in files if f.get("primary")), files[0])
        urllib.request.urlretrieve(info["url"], mods / info["filename"])


if __name__ == "__main__":
    if ctk is None or mll is None:
        root = tk.Tk()
        root.withdraw()
        messagebox.showerror("Brak zależności", "Uruchom: py -m pip install -r requirements.txt")
        sys.exit(1)
    Loader().mainloop()
