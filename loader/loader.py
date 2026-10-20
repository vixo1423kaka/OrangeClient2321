import ctypes
import http.server
import json
import os
import re
import shutil
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
SUPPORTED_VERSIONS = ["1.21.4"]   # wersje, dla których zbudowany jest mod
# Client ID własnej aplikacji Azure (patrz README). Można nadpisać zmienną środowiskową
# ORANGE_AZURE_CLIENT_ID lub polem w Ustawieniach.
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


# --- Logowanie Microsoft (własne OAuth, PKCE, przekierowanie na localhost) ----------------
def microsoft_login(client_id: str, timeout: int = 180) -> dict:
    """Otwiera przeglądarkę, czeka na kod i zwraca dane konta (id, name, access_token, refresh_token)."""
    url, state, verifier = mll.microsoft_account.get_secure_login_data(client_id, REDIRECT_URI)
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
        self.geometry("880x600")
        self.minsize(820, 560)
        self.configure(fg_color=BG)

        self.settings = load_settings()
        self.account = None  # {"id","name","refresh"}
        s = self.settings
        if s.get("account", {}).get("refresh"):
            a = s["account"]
            self.account = {"id": a.get("id", ""), "name": a.get("name", ""), "refresh": unseal(a["refresh"])}
        self.mode = tk.StringVar(value=s.get("mode", "offline"))
        self.username = tk.StringVar(value=s.get("username", "Player"))
        self.license_key = tk.StringVar(value=s.get("license_key", "") or self._key_from_instances())
        self.license_key.trace_add("write", lambda *_: self._persist())
        self.username.trace_add("write", lambda *_: self._persist())
        self._busy = False

        self._build_ui()
        self._show_play()
        self._refresh_account_label()
        if self.account:
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
        return (os.environ.get("ORANGE_AZURE_CLIENT_ID") or self.settings.get("azure_client_id")
                or AZURE_CLIENT_ID).strip()

    def _persist(self):
        self.settings["license_key"] = self.license_key.get().strip()
        self.settings["username"] = self.username.get().strip()
        self.settings["mode"] = self.mode.get()
        if self.account:
            self.settings["account"] = {"id": self.account["id"], "name": self.account["name"],
                                        "refresh": seal(self.account["refresh"])}
        else:
            self.settings.pop("account", None)
        save_settings(self.settings)

    def ui(self, fn):
        self.after(0, fn)

    # ---- UI ---------------------------------------------------------------------------------
    def _build_ui(self):
        side = ctk.CTkFrame(self, width=170, corner_radius=0, fg_color=SIDE)
        side.pack(side="left", fill="y")
        side.pack_propagate(False)
        ctk.CTkLabel(side, text="ORANGE", font=("Segoe UI", 20, "bold"), text_color=ORANGE).pack(anchor="w", padx=20, pady=(26, 0))
        ctk.CTkLabel(side, text="CLIENT", font=("Segoe UI", 11, "bold"), text_color=MUTED).pack(anchor="w", padx=20, pady=(0, 28))
        self.nav = {}
        for key, label, cmd in (("play", "Graj", self._show_play), ("settings", "Ustawienia", self._show_settings)):
            b = ctk.CTkButton(side, text=label, anchor="w", height=38, corner_radius=8,
                              fg_color="transparent", hover_color="#1b2230", text_color=MUTED,
                              font=("Segoe UI", 13), command=cmd)
            b.pack(fill="x", padx=10, pady=2)
            self.nav[key] = b

        main = ctk.CTkFrame(self, fg_color=BG, corner_radius=0)
        main.pack(side="left", fill="both", expand=True)
        self.page = ctk.CTkFrame(main, fg_color=BG, corner_radius=0)
        self.page.pack(fill="both", expand=True, padx=28, pady=(24, 8))

        dock = ctk.CTkFrame(main, fg_color=SIDE, corner_radius=0)
        dock.pack(side="bottom", fill="x")
        inner = ctk.CTkFrame(dock, fg_color="transparent")
        inner.pack(fill="x", padx=28, pady=14)
        self.play_btn = ctk.CTkButton(inner, text="▶  GRAJ", width=190, height=48, corner_radius=10,
                                      font=("Segoe UI", 15, "bold"), fg_color=ORANGE, hover_color=ORANGE_H,
                                      text_color="#101116", command=self.play)
        self.play_btn.pack(side="right")
        self.status = ctk.CTkLabel(inner, text="Gotowy.", font=("Segoe UI", 12), text_color=MUTED,
                                   anchor="w", justify="left", wraplength=420)
        self.status.pack(side="left", fill="x", expand=True)

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
                                           border_color=BORDER, button_color="#2b3446")
        self.version_box.set(self.settings.get("version", SUPPORTED_VERSIONS[0]) if self.settings.get("version") in SUPPORTED_VERSIONS else SUPPORTED_VERSIONS[0])
        self.version_box.pack(fill="x", padx=16, pady=(0, 14))

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
        self.account_label = ctk.CTkLabel(self.account_row, text="", font=("Segoe UI", 12), text_color=TEXT, anchor="w")
        self.login_btn = ctk.CTkButton(self.account_row, text="Zaloguj przez Microsoft", width=180, height=34,
                                       fg_color="#2b3446", hover_color="#38445b", command=self._login_clicked)
        self.logout_btn = ctk.CTkButton(self.account_row, text="Wyloguj", width=90, height=34,
                                        fg_color="#2b3446", hover_color="#38445b", command=self._logout)
        self._layout_account_row()

        c = self._card("KLUCZ LICENCJI")
        row = ctk.CTkFrame(c, fg_color="transparent")
        row.pack(fill="x", padx=16, pady=(0, 14))
        self.key_entry = ctk.CTkEntry(row, textvariable=self.license_key, height=36, show="•",
                                      placeholder_text="Wklej klucz (zapisze się automatycznie)", border_color=BORDER)
        self.key_entry.pack(side="left", fill="x", expand=True)
        self.show_key = ctk.CTkCheckBox(row, text="Pokaż", width=70, command=self._toggle_key)
        self.show_key.pack(side="left", padx=(12, 0))

    def _toggle_key(self):
        self.key_entry.configure(show="" if self.show_key.get() else "•")

    def _show_settings(self):
        self._set_nav("settings")
        self._clear()
        ctk.CTkLabel(self.page, text="Ustawienia", font=("Segoe UI", 26, "bold"), text_color=TEXT).pack(anchor="w", pady=(0, 8))
        c = self._card("FOLDER GRY")
        ctk.CTkLabel(c, text=str(INSTANCES), text_color=MUTED, font=("Segoe UI", 11), anchor="w").pack(fill="x", padx=16)
        ctk.CTkButton(c, text="Otwórz folder", width=130, height=32, fg_color="#2b3446", hover_color="#38445b",
                      command=self._open_dir).pack(anchor="w", padx=16, pady=(8, 14))
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
    def _on_mode(self, value):
        self.mode.set("microsoft" if value == "Microsoft" else "offline")
        self._persist()
        self._layout_account_row()

    def _layout_account_row(self):
        for w in (self.offline_entry, self.account_label, self.login_btn, self.logout_btn):
            w.pack_forget()
        if self.mode.get() == "offline":
            self.offline_entry.pack(fill="x")
            return
        if self.account:
            self.account_label.configure(text=f"● Zalogowano jako {self.account['name']}", text_color=GREEN)
            self.account_label.pack(side="left", fill="x", expand=True)
            self.logout_btn.pack(side="right")
        else:
            self.account_label.configure(text="Nie zalogowano", text_color=MUTED)
            self.account_label.pack(side="left", fill="x", expand=True)
            self.login_btn.pack(side="right")

    def _refresh_account_label(self):
        if hasattr(self, "account_row") and self.account_row.winfo_exists():
            self._layout_account_row()

    def _login_clicked(self):
        cid = self._client_id()
        if not cid:
            messagebox.showinfo("Logowanie Microsoft",
                                "Brak Azure Client ID. Wklej go w Ustawieniach (instrukcja w README loadera).")
            self._show_settings()
            return
        self.login_btn.configure(state="disabled", text="Czekam na przeglądarkę…")
        self.status.configure(text="Dokończ logowanie w przeglądarce.")
        threading.Thread(target=self._login_worker, args=(cid,), daemon=True).start()

    def _login_worker(self, cid):
        try:
            data = microsoft_login(cid)
            self.account = {"id": data["id"], "name": data["name"], "refresh": data["refresh_token"]}
            self._persist()
            self.ui(lambda: self.status.configure(text=f"Zalogowano jako {data['name']}."))
        except Exception as exc:
            msg = humanize_login_error(exc)
            self.ui(lambda: messagebox.showerror("Logowanie nie powiodło się", msg))
        finally:
            self.ui(lambda: (self.login_btn.configure(state="normal", text="Zaloguj przez Microsoft"),
                             self._refresh_account_label()))

    def _logout(self):
        self.account = None
        self._persist()
        self._layout_account_row()
        self.status.configure(text="Wylogowano.")

    def _silent_refresh(self):
        """Przy starcie odświeża sesję, żeby było widać, że konto nadal jest zalogowane."""
        cid = self._client_id()
        if not (cid and self.account):
            return
        try:
            data = mll.microsoft_account.complete_refresh(cid, None, None, self.account["refresh"])
            self.account = {"id": data["id"], "name": data["name"], "refresh": data["refresh_token"]}
            self._persist()
        except Exception:
            pass  # zostaje zapamiętane konto; błąd pojawi się dopiero przy grze
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
                self.account = {"id": data["id"], "name": data["name"], "refresh": data["refresh_token"]}
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
            if not bundled.exists():
                raise RuntimeError("Brak wbudowanego pliku moda Orange Client w loaderze.")
            shutil.copy2(bundled, mods / MOD_JAR)
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
