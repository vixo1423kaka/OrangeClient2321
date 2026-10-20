import json
import os
import re
import subprocess
import threading
import urllib.request
import urllib.parse
import shutil
import sys
import tkinter as tk
from tkinter import messagebox
from pathlib import Path

try:
    import customtkinter as ctk
    import minecraft_launcher_lib
except ImportError:
    ctk = None
    minecraft_launcher_lib = None

APP_NAME = "ORANGE CLIENT"
APPDATA = Path(os.environ.get("APPDATA", str(Path.home())))
DEFAULT_DIR = APPDATA / ".minecraft"
BG = "#090b10"
PANEL = "#111620"
CARD = "#171e2b"
MUTED = "#8792a8"
TEXT = "#f4f6fb"
ACCENTS = ["#ff7a18", "#ff8b2b", "#ff9c42", "#ff8b2b"]


def discover_game_dirs():
    candidates = [
        ("Minecraft", APPDATA / ".minecraft"),
        ("Modrinth", APPDATA / "com.modrinth.theseus"),
        ("Modrinth", APPDATA / "ModrinthApp"),
        ("Prism", APPDATA / "PrismLauncher" / "instances"),
        ("MultiMC", APPDATA / "MultiMC" / "instances"),
        ("ATLauncher", APPDATA / "ATLauncher" / "Instances"),
        ("CurseForge", Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "CurseForge" / "Minecraft" / "Instances"),
    ]
    found, seen = [], set()

    def add(label, path):
        try:
            path = path.resolve()
            if path.is_dir() and str(path).lower() not in seen:
                found.append((label, path))
                seen.add(str(path).lower())
        except OSError:
            pass

    for label, root in candidates:
        if not root.exists():
            continue
        if (root / "versions").is_dir():
            add(label, root)
        try:
            for child in root.iterdir():
                if not child.is_dir():
                    continue
                for candidate in (child, child / ".minecraft", child / "minecraft", child / "game"):
                    if (candidate / "versions").is_dir():
                        add(f"{label} · {child.name}", candidate)
                try:
                    for sub in child.iterdir():
                        if sub.is_dir():
                            for candidate in (sub, sub / ".minecraft", sub / "minecraft"):
                                if (candidate / "versions").is_dir():
                                    add(f"{label} · {child.name}/{sub.name}", candidate)
                except OSError:
                    pass
        except OSError:
            pass
    if not found:
        add("Minecraft", DEFAULT_DIR)
    return found


def available_versions():
    try:
        req = urllib.request.Request(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            headers={"User-Agent": "OrangeClientLoader/1.0"})
        with urllib.request.urlopen(req, timeout=12) as response:
            manifest = json.loads(response.read().decode("utf-8"))
        out = []
        for item in manifest.get("versions", []):
            vid = item.get("id", "")
            if item.get("type") == "release" and re.match(r"^1\.\d+\.\d+$", vid):
                nums = tuple(int(x) for x in re.findall(r"\d+", vid))
                if nums >= (1, 21, 4):
                    out.append(vid)
        return out or ["1.21.4"]
    except Exception:
        return ["1.21.4"]


class Loader(ctk.CTk if ctk else object):
    def __init__(self):
        if ctk is None:
            raise RuntimeError("Brak customtkinter")
        ctk.set_appearance_mode("dark")
        ctk.set_default_color_theme("dark-blue")
        super().__init__()
        self.title("Orange Client")
        self.geometry("1000x720")
        self.minsize(850, 650)
        self.configure(fg_color=BG)
        self.game_dirs = discover_game_dirs()
        self.current_dir = self.game_dirs[0][1] if self.game_dirs else DEFAULT_DIR
        self.version_items = available_versions()
        self.mode = ctk.StringVar(value="offline")
        self.username = ctk.StringVar(value="Player")
        self.license_key = ctk.StringVar()
        self._anim_index = 0
        self._busy = False
        self._build_ui()
        self.refresh_versions()
        self._animate_accent()

    def _build_ui(self):
        # Left navigation rail
        self.sidebar = ctk.CTkFrame(self, width=205, corner_radius=0, fg_color="#0d1119")
        self.sidebar.pack(side="left", fill="y")
        self.sidebar.pack_propagate(False)

        brand = ctk.CTkFrame(self.sidebar, fg_color="transparent")
        brand.pack(fill="x", padx=21, pady=(27, 30))
        ctk.CTkLabel(brand, text="◈", font=("Segoe UI Symbol", 28, "bold"),
                     text_color="#ff8526").pack(side="left", padx=(0, 9))
        brand_words = ctk.CTkFrame(brand, fg_color="transparent")
        brand_words.pack(side="left")
        ctk.CTkLabel(brand_words, text="ORANGE", font=("Segoe UI", 19, "bold"),
                     text_color=TEXT).pack(anchor="w")
        ctk.CTkLabel(brand_words, text="CLIENT  /  LOADER", font=("Segoe UI", 9, "bold"),
                     text_color=MUTED).pack(anchor="w", pady=(0, 1))

        ctk.CTkLabel(self.sidebar, text="MENU GŁÓWNE", font=("Segoe UI", 9, "bold"),
                     text_color="#667187").pack(anchor="w", padx=22, pady=(0, 8))
        self.nav_play = ctk.CTkButton(self.sidebar, text="   ▶    Graj", anchor="w",
                                      height=42, corner_radius=9, fg_color="#24202a",
                                      hover_color="#30232a", text_color="#ff9948",
                                      font=("Segoe UI", 12, "bold"), command=self._show_play)
        self.nav_play.pack(fill="x", padx=12, pady=3)
        ctk.CTkButton(self.sidebar, text="   ◉    Instalacje", anchor="w", height=42,
                      corner_radius=9, fg_color="transparent", hover_color="#1a202b",
                      text_color="#a7b0c2", font=("Segoe UI", 12),
                      command=self._show_installs).pack(fill="x", padx=12, pady=3)
        ctk.CTkButton(self.sidebar, text="   ⚙    Ustawienia", anchor="w", height=42,
                      corner_radius=9, fg_color="transparent", hover_color="#1a202b",
                      text_color="#a7b0c2", font=("Segoe UI", 12),
                      command=self._show_settings).pack(fill="x", padx=12, pady=3)

        spacer = ctk.CTkFrame(self.sidebar, fg_color="transparent")
        spacer.pack(fill="both", expand=True)
        self.side_status = ctk.CTkFrame(self.sidebar, fg_color="#141a25",
                                        corner_radius=11, border_width=1,
                                        border_color="#202939")
        self.side_status.pack(fill="x", padx=12, pady=(8, 12))
        ctk.CTkLabel(self.side_status, text="STATUS LAUNCHERA", font=("Segoe UI", 9, "bold"),
                     text_color="#78859b").pack(anchor="w", padx=12, pady=(12, 5))
        status_row = ctk.CTkFrame(self.side_status, fg_color="transparent")
        status_row.pack(fill="x", padx=12, pady=(0, 12))
        ctk.CTkLabel(status_row, text="●", text_color="#69d69a",
                     font=("Segoe UI", 12)).pack(side="left", padx=(0, 7))
        ctk.CTkLabel(status_row, text="Gotowy do uruchomienia", text_color="#c5ccda",
                     font=("Segoe UI", 10)).pack(side="left")
        ctk.CTkLabel(self.sidebar, text="ORANGE CLIENT  •  WINDOWS", font=("Segoe UI", 9),
                     text_color="#596579").pack(pady=(0, 18))

        # Main content; the launch dock is a separate fixed region so Play is never below the fold.
        self.content = ctk.CTkFrame(self, fg_color=BG, corner_radius=0)
        self.content.pack(side="left", fill="both", expand=True)
        self.topbar = ctk.CTkFrame(self.content, fg_color=BG, corner_radius=0)
        self.topbar.pack(fill="x", padx=30, pady=(22, 8))
        ctk.CTkLabel(self.topbar, text="ORANGE  /  GAME CENTER", font=("Segoe UI", 10, "bold"),
                     text_color="#ff9140").pack(side="left")
        ctk.CTkLabel(self.topbar, text="FABRIC EDITION", font=("Segoe UI", 10, "bold"),
                     text_color="#78859b").pack(side="right")

        self.page_area = ctk.CTkFrame(self.content, fg_color=BG, corner_radius=0)
        self.page_area.pack(fill="both", expand=True, padx=30, pady=(0, 8))
        self._build_play_page()

        self.dock = ctk.CTkFrame(self.content, fg_color="#10151e", corner_radius=0,
                                 border_width=1, border_color="#202938")
        self.dock.pack(side="bottom", fill="x")
        dock_inner = ctk.CTkFrame(self.dock, fg_color="transparent")
        dock_inner.pack(fill="x", padx=30, pady=15)
        self.play_btn = ctk.CTkButton(
            dock_inner, text="▶     PLAY  /  URUCHOM MINECRAFT", height=54,
            corner_radius=12, font=("Segoe UI", 14, "bold"),
            fg_color="#f47c20", hover_color="#ff963f", text_color="#101116",
            command=self.play)
        self.play_btn.pack(side="right", padx=(18, 0))
        dock_info = ctk.CTkFrame(dock_inner, fg_color="transparent")
        dock_info.pack(side="left", fill="x", expand=True)
        ctk.CTkLabel(dock_info, text="GOTOWY DO GRY?", font=("Segoe UI", 10, "bold"),
                     text_color="#ff9948").pack(anchor="w")
        self.dock_status = ctk.CTkLabel(dock_info, text="Wybierz wersję i wpisz klucz licencji.",
                                        font=("Segoe UI", 11), text_color="#a1abc0",
                                        wraplength=390, justify="left")
        self.dock_status.pack(anchor="w", pady=(3, 0))

    def _build_play_page(self):
        ctk.CTkLabel(self.page_area, text="Twój kolejny świat.", font=("Segoe UI", 30, "bold"),
                     text_color=TEXT).pack(anchor="w", pady=(13, 2))
        ctk.CTkLabel(self.page_area, text="Szybki start. Czysta konfiguracja. Orange Client.",
                     font=("Segoe UI", 12), text_color=MUTED).pack(anchor="w", pady=(0, 17))

        hero = ctk.CTkFrame(self.page_area, fg_color=CARD, corner_radius=15,
                            border_width=1, border_color="#283243")
        hero.pack(fill="x", pady=(0, 15))
        hero_top = ctk.CTkFrame(hero, fg_color="transparent")
        hero_top.pack(fill="x", padx=18, pady=(15, 12))
        emblem = ctk.CTkFrame(hero_top, width=48, height=48, corner_radius=12,
                              fg_color="#382316")
        emblem.pack(side="left", padx=(0, 12))
        emblem.pack_propagate(False)
        ctk.CTkLabel(emblem, text="◈", font=("Segoe UI Symbol", 27, "bold"),
                     text_color="#ff8a28").place(relx=.5, rely=.48, anchor="center")
        hero_text = ctk.CTkFrame(hero_top, fg_color="transparent")
        hero_text.pack(side="left", fill="x", expand=True)
        ctk.CTkLabel(hero_text, text="ORANGE CLIENT", font=("Segoe UI", 17, "bold"),
                     text_color=TEXT).pack(anchor="w")
        ctk.CTkLabel(hero_text, text="MODDED MINECRAFT EXPERIENCE",
                     font=("Segoe UI", 9, "bold"), text_color="#8d9ab0").pack(anchor="w", pady=(3, 0))
        self.hero_badge = ctk.CTkLabel(hero_top, text="● ONLINE MANIFEST", corner_radius=7,
                                      fg_color="#1b302b", text_color="#82dfb1",
                                      font=("Segoe UI", 9, "bold"), padx=9, pady=6)
        self.hero_badge.pack(side="right", anchor="n")
        self.accent_line = ctk.CTkFrame(hero, height=3, fg_color="#ff7a18", corner_radius=0)
        self.accent_line.pack(fill="x", side="bottom")

        # Two-column control cards
        grid = ctk.CTkFrame(self.page_area, fg_color="transparent")
        grid.pack(fill="x")
        grid.grid_columnconfigure(0, weight=1, uniform="col")
        grid.grid_columnconfigure(1, weight=1, uniform="col")

        version_card = ctk.CTkFrame(grid, fg_color=PANEL, corner_radius=13,
                                    border_width=1, border_color="#202a39")
        version_card.grid(row=0, column=0, sticky="nsew", padx=(0, 7), pady=(0, 12))
        ctk.CTkLabel(version_card, text="01  /  WERSJA GRY", font=("Segoe UI", 10, "bold"),
                     text_color="#ff9846").pack(anchor="w", padx=15, pady=(14, 7))
        self.version_box = ctk.CTkComboBox(version_card, values=self.version_items or ["1.21.4"],
                                           height=39, corner_radius=8, state="readonly",
                                           border_color="#303a4b", button_color="#343e50",
                                           button_hover_color="#46536a")
        self.version_box.pack(fill="x", padx=14)
        self.version_box.set(self.version_items[0] if self.version_items else "1.21.4")
        ctk.CTkLabel(version_card, text="Wersje release od 1.21.4",
                     font=("Segoe UI", 10), text_color=MUTED).pack(anchor="w", padx=15, pady=(7, 13))

        login_card = ctk.CTkFrame(grid, fg_color=PANEL, corner_radius=13,
                                  border_width=1, border_color="#202a39")
        login_card.grid(row=0, column=1, sticky="nsew", padx=(7, 0), pady=(0, 12))
        ctk.CTkLabel(login_card, text="02  /  PROFIL GRACZA", font=("Segoe UI", 10, "bold"),
                     text_color="#ff9846").pack(anchor="w", padx=15, pady=(14, 7))
        self.mode_box = ctk.CTkSegmentedButton(login_card, values=["Offline", "Microsoft"],
                                               command=self.on_mode, height=39,
                                               selected_color="#e8751e",
                                               selected_hover_color="#ff963f",
                                               unselected_color="#202938",
                                               unselected_hover_color="#2b3648")
        self.mode_box.pack(fill="x", padx=14)
        self.mode_box.set("Offline")
        self.username_entry = ctk.CTkEntry(login_card, textvariable=self.username, height=35,
                                           corner_radius=8, placeholder_text="Nazwa gracza offline",
                                           border_color="#303a4b")
        self.username_entry.pack(fill="x", padx=14, pady=(8, 13))

        license_card = ctk.CTkFrame(self.page_area, fg_color=PANEL, corner_radius=13,
                                    border_width=1, border_color="#202a39")
        license_card.pack(fill="x", pady=(0, 12))
        license_head = ctk.CTkFrame(license_card, fg_color="transparent")
        license_head.pack(fill="x", padx=15, pady=(13, 7))
        ctk.CTkLabel(license_head, text="03  /  KLUCZ LICENCJI", font=("Segoe UI", 10, "bold"),
                     text_color="#ff9846").pack(side="left")
        ctk.CTkLabel(license_head, text="WYMAGANY", font=("Segoe UI", 9, "bold"),
                     text_color="#d6a06e").pack(side="right")
        self.license_entry = ctk.CTkEntry(license_card, textvariable=self.license_key,
                                          height=39, corner_radius=8, show="•",
                                          placeholder_text="Wklej klucz Orange Client",
                                          border_color="#303a4b")
        self.license_entry.pack(fill="x", padx=14, pady=(0, 14))

        self.page_note = ctk.CTkLabel(self.page_area,
            text="Instalator tworzy osobny profil Orange Client i nie modyfikuje folderu mods z innych launcherów.",
            font=("Segoe UI", 10), text_color="#7f8ba0", wraplength=700, justify="left")
        self.page_note.pack(anchor="w", pady=(0, 3))

    def _clear_page(self):
        for widget in self.page_area.winfo_children():
            widget.destroy()

    def _show_play(self):
        self._clear_page()
        self._build_play_page()

    def _show_installs(self):
        self._clear_page()
        ctk.CTkLabel(self.page_area, text="Wykryte instalacje", font=("Segoe UI", 27, "bold"),
                     text_color=TEXT).pack(anchor="w", pady=(18, 5))
        ctk.CTkLabel(self.page_area, text="Loader sprawdza typowe lokalizacje launcherów automatycznie.",
                     font=("Segoe UI", 12), text_color=MUTED).pack(anchor="w", pady=(0, 18))
        if not self.game_dirs:
            entries = [("Nie wykryto istniejącej instalacji", DEFAULT_DIR)]
        else:
            entries = self.game_dirs
        for label, path in entries:
            card = ctk.CTkFrame(self.page_area, fg_color=PANEL, corner_radius=11,
                                border_width=1, border_color="#202a39")
            card.pack(fill="x", pady=5)
            ctk.CTkLabel(card, text="●  " + label, font=("Segoe UI", 12, "bold"),
                         text_color=TEXT).pack(anchor="w", padx=15, pady=(12, 2))
            ctk.CTkLabel(card, text=str(path), font=("Segoe UI", 10),
                         text_color=MUTED, wraplength=650, justify="left").pack(anchor="w", padx=15, pady=(0, 12))
        ctk.CTkLabel(self.page_area, text="Orange Client uruchamia się w oddzielnym profilu, aby nie nadpisywać cudzych modów.",
                     font=("Segoe UI", 11), text_color="#ffad69", wraplength=700).pack(anchor="w", pady=16)

    def _show_settings(self):
        self._clear_page()
        ctk.CTkLabel(self.page_area, text="Ustawienia", font=("Segoe UI", 27, "bold"),
                     text_color=TEXT).pack(anchor="w", pady=(18, 5))
        ctk.CTkLabel(self.page_area, text="Informacje o aktualnej konfiguracji launchera.",
                     font=("Segoe UI", 12), text_color=MUTED).pack(anchor="w", pady=(0, 18))
        for title, detail in [
            ("Katalog gry", str(APPDATA / "OrangeClientLoader" / "instances")),
            ("Silnik modów", "Fabric Loader"),
            ("Zakres wersji", "Minecraft release 1.21.4 i nowsze na liście; sam mod wymaga portu na nowsze wydania."),
            ("Konto", "Tryb offline jest dostępny. Własne logowanie Microsoft OAuth nie jest jeszcze zintegrowane."),
        ]:
            card = ctk.CTkFrame(self.page_area, fg_color=PANEL, corner_radius=11,
                                border_width=1, border_color="#202a39")
            card.pack(fill="x", pady=5)
            ctk.CTkLabel(card, text=title.upper(), font=("Segoe UI", 9, "bold"),
                         text_color="#ff9846").pack(anchor="w", padx=15, pady=(11, 3))
            ctk.CTkLabel(card, text=detail, font=("Segoe UI", 11), text_color="#c2cada",
                         wraplength=670, justify="left").pack(anchor="w", padx=15, pady=(0, 11))

    def _animate_accent(self):
        if not self.winfo_exists():
            return
        color = ACCENTS[self._anim_index % len(ACCENTS)]
        self._anim_index += 1
        try:
            self.accent_line.configure(fg_color=color)
            self.nav_play.configure(border_width=1, border_color=color)
            self.after(520, self._animate_accent)
        except tk.TclError:
            pass

    def on_mode(self, value):
        is_offline = value == "Offline"
        self.mode.set("offline" if is_offline else "microsoft")
        self.username_entry.configure(state="normal" if is_offline else "disabled")
        if is_offline:
            self.dock_status.configure(text="Tryb offline: serwery mogą wymagać konta premium.")
        else:
            self.dock_status.configure(text="Microsoft OAuth nie jest jeszcze zintegrowany. Przycisk Play otworzy oficjalny launcher.")
    
    def refresh_versions(self):
        self.version_items = available_versions()
        self.version_box.configure(values=self.version_items or ["1.21.4"])
        self.version_box.set(self.version_items[0] if self.version_items else "1.21.4")
        self.dock_status.configure(text=f"Wykryto {len(self.game_dirs)} lokalizacji launcherów. Wybierz wersję i wpisz klucz.")

    def play(self):
        if self._busy:
            return
        if self.mode.get() == "microsoft":
            candidates = [
                Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Minecraft Launcher" / "MinecraftLauncher.exe",
                APPDATA / "Minecraft Launcher" / "MinecraftLauncher.exe",
                Path(os.environ.get("ProgramFiles(x86)", "C:/Program Files (x86)")) / "Minecraft Launcher" / "MinecraftLauncher.exe",
            ]
            exe = next((p for p in candidates if p.exists()), None)
            if exe:
                try:
                    subprocess.Popen([str(exe)])
                    messagebox.showinfo("Logowanie Microsoft",
                        "Otworzyłem oficjalny launcher. Zaloguj się tam i uruchom właściwy profil Fabric.\n\nTen prototyp nie przejmuje sesji ani tokenów z innego launchera.")
                except OSError as exc:
                    messagebox.showerror("Błąd", str(exc))
            else:
                messagebox.showinfo("Microsoft", "Nie znaleziono oficjalnego launchera. Własne logowanie Microsoft OAuth nie jest jeszcze zaimplementowane.")
            return
        if not minecraft_launcher_lib:
            messagebox.showerror("Brak zależności", "Uruchom build.bat, aby zainstalować wymagane biblioteki.")
            return
        version, username, key = self.version_box.get().strip(), self.username.get().strip(), self.license_key.get().strip()
        if version not in self.version_items:
            messagebox.showerror("Brak wersji", "Wybierz wersję z listy.")
            return
        if not re.fullmatch(r"[A-Za-z0-9_]{3,16}", username):
            messagebox.showerror("Nieprawidłowa nazwa", "Nazwa offline musi mieć 3–16 znaków: litery, cyfry lub podkreślenie.")
            return
        if not key:
            messagebox.showerror("Brak licencji", "Wpisz klucz licencji Orange Client.")
            return
        self.current_dir = APPDATA / "OrangeClientLoader" / "instances" / version
        try:
            config = self.current_dir / "config"
            config.mkdir(parents=True, exist_ok=True)
            (config / "orangeclient_license.json").write_text(json.dumps({"key": key}), encoding="utf-8")
        except OSError as exc:
            messagebox.showerror("Błąd zapisu", str(exc))
            return
        self._busy = True
        self.play_btn.configure(state="disabled", text="◌   PRZYGOTOWYWANIE GRY…")
        self.dock_status.configure(text="Pobieranie plików gry i przygotowanie profilu Fabric…")
        threading.Thread(target=self._launch, args=(version, username), daemon=True).start()

    def _launch(self, version, username):
        try:
            minecraft_launcher_lib.install.install_minecraft_version(version, str(self.current_dir))
            launch_version = version
            if version == "1.21.4":
                minecraft_launcher_lib.fabric.install_fabric(version, str(self.current_dir))
                installed = minecraft_launcher_lib.utils.get_installed_versions(str(self.current_dir))
                profiles = [v.get("id") for v in installed if "fabric" in v.get("id", "").lower() and "1.21.4" in v.get("id", "")]
                if profiles:
                    launch_version = profiles[0]
                bundled = Path(getattr(sys, "_MEIPASS", Path(__file__).parent)) / "client-mods" / "orange-client-0.1.0.jar"
                mods = self.current_dir / "mods"
                mods.mkdir(parents=True, exist_ok=True)
                if bundled.exists():
                    shutil.copy2(bundled, mods / "orange-client-0.1.0.jar")
                else:
                    raise RuntimeError("Brak wbudowanego pliku moda Orange Client w loaderze.")
                api = "https://api.modrinth.com/v2/project/fabric-api/version?" + urllib.parse.urlencode({
                    "game_versions": json.dumps(["1.21.4"]), "loaders": json.dumps(["fabric"])})
                req = urllib.request.Request(api, headers={"User-Agent": "OrangeClientLoader/1.0"})
                with urllib.request.urlopen(req, timeout=20) as response:
                    api_versions = json.loads(response.read().decode("utf-8"))
                if api_versions:
                    files = api_versions[0].get("files", [])
                    if files:
                        file_info = next((file for file in files if file.get("primary")), files[0])
                        urllib.request.urlretrieve(file_info["url"], mods / file_info["filename"])
            else:
                raise RuntimeError("Orange Client jest obecnie zbudowany dla Minecraft 1.21.4. Inne wersje są widoczne na liście, ale wymagają portu moda.")
            options = {"username": username, "uuid": "00000000-0000-0000-0000-000000000000",
                       "token": "", "gameDirectory": str(self.current_dir)}
            command = minecraft_launcher_lib.command.get_minecraft_command(launch_version, str(self.current_dir), options)
            subprocess.Popen(command, cwd=str(self.current_dir))
            self.after(0, lambda: self.dock_status.configure(text="Proces gry uruchomiony. Weryfikacja licencji odbywa się po starcie klienta."))
        except Exception as exc:
            error = str(exc)
            self.after(0, lambda error=error: messagebox.showerror("Nie udało się uruchomić", error))
            self.after(0, lambda: self.dock_status.configure(text="Uruchomienie nie powiodło się. Sprawdź komunikat błędu."))
        finally:
            def finish():
                self._busy = False
                self.play_btn.configure(state="normal", text="▶     PLAY  /  URUCHOM MINECRAFT")
            self.after(0, finish)


if __name__ == "__main__":
    if ctk is None or minecraft_launcher_lib is None:
        root = tk.Tk()
        root.withdraw()
        messagebox.showerror("Brak zależności", "Zbuduj loader przez build.bat lub uruchom: py -m pip install -r requirements.txt")
        root.destroy()
        sys.exit(1)
    Loader().mainloop()
