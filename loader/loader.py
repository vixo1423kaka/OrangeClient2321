import json
import os
import re
import subprocess
import threading
import urllib.request
import urllib.parse
import shutil
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
        ctk.set_default_color_theme("blue")
        super().__init__()
        self.title("Orange Client | Launcher")
        self.geometry("900x650")
        self.minsize(800, 600)
        self.configure(fg_color="#0b0d12")
        self.game_dirs = discover_game_dirs()
        self.current_dir = self.game_dirs[0][1] if self.game_dirs else DEFAULT_DIR
        self.version_items = available_versions()
        self.mode = ctk.StringVar(value="offline")
        self.username = ctk.StringVar(value="Player")
        self.license_key = ctk.StringVar()
        self._ui()
        self.refresh_versions()

    def _ui(self):
        sidebar = ctk.CTkFrame(self, width=220, corner_radius=0, fg_color="#10131b")
        sidebar.pack(side="left", fill="y")
        sidebar.pack_propagate(False)
        ctk.CTkLabel(sidebar, text="ORANGE", font=("Segoe UI", 28, "bold"),
                     text_color="#ff8a24").pack(anchor="w", padx=24, pady=(30, 0))
        ctk.CTkLabel(sidebar, text="CLIENT  /  LOADER", font=("Segoe UI", 11, "bold"),
                     text_color="#8b93a7").pack(anchor="w", padx=26, pady=(0, 34))
        for title, desc in [("PLAY", "Uruchamianie gry"), ("INSTALACJE", "Wersje i launchery"),
                            ("LICENCJA", "Weryfikacja Orange Client")]:
            card = ctk.CTkFrame(sidebar, fg_color="#191e29", corner_radius=10)
            card.pack(fill="x", padx=14, pady=5)
            ctk.CTkLabel(card, text=title, font=("Segoe UI", 12, "bold"),
                         text_color="#f3f5fa").pack(anchor="w", padx=13, pady=(10, 0))
            ctk.CTkLabel(card, text=desc, font=("Segoe UI", 10),
                         text_color="#8b93a7").pack(anchor="w", padx=13, pady=(0, 10))
        ctk.CTkLabel(sidebar, text="WINDOWS • FABRIC", text_color="#697286",
                     font=("Segoe UI", 10)).pack(side="bottom", pady=20)

        main = ctk.CTkFrame(self, fg_color="transparent")
        main.pack(side="left", fill="both", expand=True, padx=30, pady=26)
        ctk.CTkLabel(main, text="Witaj w Orange Client", font=("Segoe UI", 27, "bold"),
                     text_color="#f4f6fb").pack(anchor="w")
        ctk.CTkLabel(main, text="Wybierz instalację, konto i uruchom grę.",
                     font=("Segoe UI", 13), text_color="#939bad").pack(anchor="w", pady=(4, 22))

        hero = ctk.CTkFrame(main, fg_color="#171c27", corner_radius=16, border_width=1,
                            border_color="#292f3e")
        hero.pack(fill="x", pady=(0, 18))
        ctk.CTkLabel(hero, text="GOTOWY DO GRY?", font=("Segoe UI", 12, "bold"),
                     text_color="#ff9b45").pack(anchor="w", padx=20, pady=(18, 2))
        ctk.CTkLabel(hero, text="Twoja konfiguracja Minecraft", font=("Segoe UI", 19, "bold"),
                     text_color="#f4f6fb").pack(anchor="w", padx=20, pady=(0, 16))

        ctk.CTkLabel(main, text="INSTALACJA GRY", font=("Segoe UI", 11, "bold"),
                     text_color="#a8b0c2").pack(anchor="w", pady=(0, 6))
        install_row = ctk.CTkFrame(main, fg_color="transparent")
        install_row.pack(fill="x", pady=(0, 14))
        labels = [f"{label}  —  {path}" for label, path in self.game_dirs]
        if not labels:
            labels = [str(DEFAULT_DIR)]
            self.game_dirs = [("Domyślny katalog Minecraft", DEFAULT_DIR)]
        self.install_box = ctk.CTkComboBox(install_row, values=labels, command=self.on_install,
                                           height=40, corner_radius=9, state="readonly")
        self.install_box.pack(side="left", fill="x", expand=True)
        self.install_box.set(labels[0])
        ctk.CTkLabel(install_row, text="Wykryte automatycznie", text_color="#7f8ba3", font=("Segoe UI", 10)).pack(anchor="w", pady=(4, 0))

        row = ctk.CTkFrame(main, fg_color="transparent")
        row.pack(fill="x", pady=(0, 14))
        left = ctk.CTkFrame(row, fg_color="transparent")
        left.pack(side="left", fill="x", expand=True, padx=(0, 8))
        right = ctk.CTkFrame(row, fg_color="transparent")
        right.pack(side="left", fill="x", expand=True, padx=(8, 0))
        ctk.CTkLabel(left, text="WERSJA MINECRAFT / FABRIC", font=("Segoe UI", 11, "bold"),
                     text_color="#a8b0c2").pack(anchor="w", pady=(0, 6))
        self.version_box = ctk.CTkComboBox(left, values=self.version_items or ["1.21.4"], height=40, corner_radius=9, state="readonly")
        self.version_box.pack(fill="x")
        self.version_box.set(self.version_items[0] if self.version_items else "1.21.4")
        ctk.CTkLabel(left, text="Wydania od 1.21.4 do najnowszego", text_color="#7f8ba3", font=("Segoe UI", 10)).pack(anchor="w", pady=(6, 0))
        ctk.CTkLabel(right, text="SPOSÓB LOGOWANIA", font=("Segoe UI", 11, "bold"),
                     text_color="#a8b0c2").pack(anchor="w", pady=(0, 6))
        self.mode_box = ctk.CTkSegmentedButton(right, values=["Offline", "Microsoft"],
                                               command=self.on_mode, height=40,
                                               selected_color="#f08024", selected_hover_color="#d86a12")
        self.mode_box.pack(fill="x")
        self.mode_box.set("Offline")

        ctk.CTkLabel(main, text="NAZWA GRACZA (OFFLINE)", font=("Segoe UI", 11, "bold"),
                     text_color="#a8b0c2").pack(anchor="w", pady=(0, 6))
        self.username_entry = ctk.CTkEntry(main, textvariable=self.username, height=40,
                                            corner_radius=9, placeholder_text="np. OrangePlayer")
        self.username_entry.pack(fill="x", pady=(0, 14))
        ctk.CTkLabel(main, text="KLUCZ LICENCJI", font=("Segoe UI", 11, "bold"),
                     text_color="#a8b0c2").pack(anchor="w", pady=(0, 6))
        ctk.CTkEntry(main, textvariable=self.license_key, height=40, corner_radius=9,
                     show="•", placeholder_text="Wklej klucz Orange Client").pack(fill="x", pady=(0, 16))
        self.play_btn = ctk.CTkButton(main, text="▶    ZAPISZ LICENCJĘ I GRAJ",
                                      height=48, corner_radius=11, font=("Segoe UI", 14, "bold"),
                                      fg_color="#f08024", hover_color="#d86a12",
                                      command=self.play)
        self.play_btn.pack(fill="x")
        self.status = ctk.CTkLabel(main, text="Gotowy", text_color="#929bad",
                                   font=("Segoe UI", 11), wraplength=620, justify="left")
        self.status.pack(anchor="w", pady=(12, 0))

    def on_install(self, value):
        for label, path in self.game_dirs:
            if value.startswith(label + "  —") or value == label:
                self.current_dir = path
                break
        self.refresh_versions()

    def on_mode(self, value):
        is_offline = value == "Offline"
        self.mode.set("offline" if is_offline else "microsoft")
        self.username_entry.configure(state="normal" if is_offline else "disabled")
        if is_offline:
            self.status.configure(text="Tryb offline: działa tylko na światach i serwerach dopuszczających konta offline.")
        else:
            self.status.configure(text="Microsoft: ta wersja loadera nie ma jeszcze własnego OAuth. Kliknij Play, aby otworzyć oficjalny launcher i zalogować się tam; uruchomienie profilu Microsoft bezpiecznie wymaga integracji OAuth.")
 
    def refresh_versions(self):
        self.version_items = available_versions()
        self.version_box.configure(values=self.version_items or ["1.21.4"])
        self.version_box.set(self.version_items[0] if self.version_items else "1.21.4")
        self.status.configure(text=f"Wersje pobierane z manifestu Mojang. Wykryto {len(self.game_dirs)} lokalizacji launcherów.")

    def play(self):
        if self.mode.get() == "microsoft":
            candidates = [
                Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Minecraft Launcher" / "MinecraftLauncher.exe",
                APPDATA / "Minecraft Launcher" / "MinecraftLauncher.exe",
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
                messagebox.showinfo("Microsoft", "Nie znaleziono oficjalnego launchera. Zainstaluj go ze strony Minecraft. Własne logowanie Microsoft OAuth nie jest jeszcze zaimplementowane.")
            return
        if not minecraft_launcher_lib:
            messagebox.showerror("Brak zależności", "Uruchom build.bat, aby zainstalować wymagane biblioteki.")
            return
        version, username, key = self.version_box.get().strip(), self.username.get().strip(), self.license_key.get().strip()
        if version not in self.version_items:
            messagebox.showerror("Brak wersji", "Wybierz zainstalowaną wersję Fabric.")
            return
        if not re.fullmatch(r"[A-Za-z0-9_]{3,16}", username):
            messagebox.showerror("Nieprawidłowa nazwa", "Nazwa offline musi mieć 3–16 znaków: litery, cyfry lub podkreślenie.")
            return
        if not key:
            messagebox.showerror("Brak licencji", "Wpisz klucz licencji Orange Client.")
            return
        # Dedicated loader instance: don't alter the user's existing launcher folders.
        self.current_dir = APPDATA / "OrangeClientLoader" / "instances" / version
        try:
            config = self.current_dir / "config"
            config.mkdir(parents=True, exist_ok=True)
            (config / "orangeclient_license.json").write_text(json.dumps({"key": key}), encoding="utf-8")
        except OSError as exc:
            messagebox.showerror("Błąd zapisu", str(exc))
            return
        self.play_btn.configure(state="disabled", text="URUCHAMIANIE…")
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
                # The mod jar is bundled by the CI build into the EXE.
                bundled = Path(getattr(sys, "_MEIPASS", Path(__file__).parent)) / "client-mods" / "orange-client-0.1.0.jar"
                mods = self.current_dir / "mods"
                mods.mkdir(parents=True, exist_ok=True)
                if bundled.exists():
                    shutil.copy2(bundled, mods / "orange-client-0.1.0.jar")
                else:
                    raise RuntimeError("Brak wbudowanego pliku moda Orange Client w loaderze.")
                # Fetch Fabric API for this Minecraft release from Modrinth.
                api = "https://api.modrinth.com/v2/project/fabric-api/version?" + urllib.parse.urlencode({
                    "game_versions": json.dumps(["1.21.4"]), "loaders": json.dumps(["fabric"])})
                req = urllib.request.Request(api, headers={"User-Agent": "OrangeClientLoader/1.0"})
                with urllib.request.urlopen(req, timeout=20) as response:
                    api_versions = json.loads(response.read().decode("utf-8"))
                if api_versions:
                    file_info = next((file for file in api_versions[0].get("files", []) if file.get("primary")), api_versions[0]["files"][0])
                    api_jar = mods / file_info["filename"]
                    urllib.request.urlretrieve(file_info["url"], api_jar)
            elif version != "1.21.4":
                raise RuntimeError("Orange Client jest obecnie skonfigurowany dla Minecraft 1.21.4. Nowsze wersje można wybrać z listy, ale mod wymaga portu na te wersje.")
            options = {"username": username, "uuid": "00000000-0000-0000-0000-000000000000",
                       "token": "", "gameDirectory": str(self.current_dir)}
            command = minecraft_launcher_lib.command.get_minecraft_command(launch_version, str(self.current_dir), options)
            subprocess.Popen(command, cwd=str(self.current_dir))
            self.after(0, lambda: self.status.configure(text="Gra uruchomiona. Orange Client sprawdza licencję po starcie."))
        except Exception as exc:
            self.after(0, lambda: messagebox.showerror("Nie udało się uruchomić", str(exc)))
            self.after(0, lambda: self.status.configure(text="Uruchomienie nie powiodło się."))
        finally:
            self.after(0, lambda: self.play_btn.configure(state="normal", text="▶    ZAPISZ LICENCJĘ I GRAJ"))


if __name__ == "__main__":
    if ctk is None or minecraft_launcher_lib is None:
        root = tk.Tk()
        root.withdraw()
        messagebox.showerror("Brak zależności", "Zbuduj loader przez build.bat lub uruchom: py -m pip install -r requirements.txt")
        root.destroy()
        sys.exit(1)
    Loader().mainloop()
