import json
import os
import re
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import ttk, messagebox
from pathlib import Path

try:
    import minecraft_launcher_lib
except ImportError:
    minecraft_launcher_lib = None

APP_NAME = "Orange Client Loader"
DEFAULT_DIR = Path(os.environ.get("APPDATA", str(Path.home()))) / ".minecraft"


def safe_username(name):
    return bool(re.fullmatch(r"[A-Za-z0-9_]{3,16}", name or ""))


class Loader(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(APP_NAME)
        self.geometry("560x390")
        self.minsize(520, 360)
        self.game_dir = DEFAULT_DIR
        self.versions = []
        self.version_var = tk.StringVar()
        self.username_var = tk.StringVar(value="Player")
        self.key_var = tk.StringVar()
        self.status_var = tk.StringVar(value="Wybierz zainstalowaną wersję Fabric.")
        self._build()
        self.refresh_versions()

    def _build(self):
        root = ttk.Frame(self, padding=18)
        root.pack(fill="both", expand=True)
        ttk.Label(root, text="ORANGE CLIENT", font=("Segoe UI", 18, "bold")).pack(anchor="w")
        ttk.Label(root, text="Loader — prototyp Windows", foreground="#777").pack(anchor="w", pady=(0, 14))

        ttk.Label(root, text="Zainstalowana wersja Minecraft / Fabric").pack(anchor="w")
        row = ttk.Frame(root)
        row.pack(fill="x", pady=(4, 12))
        self.version_box = ttk.Combobox(row, textvariable=self.version_var, state="readonly")
        self.version_box.pack(side="left", fill="x", expand=True)
        ttk.Button(row, text="Odśwież", command=self.refresh_versions).pack(side="left", padx=(8, 0))

        ttk.Label(root, text="Nazwa gracza (tryb offline, 3–16 znaków)").pack(anchor="w")
        ttk.Entry(root, textvariable=self.username_var).pack(fill="x", pady=(4, 12))

        ttk.Label(root, text="Klucz licencji Orange Client").pack(anchor="w")
        ttk.Entry(root, textvariable=self.key_var, show="•").pack(fill="x", pady=(4, 14))

        self.play_button = ttk.Button(root, text="ZAPISZ KLUCZ I GRAJ", command=self.play)
        self.play_button.pack(fill="x", ipady=6)
        ttk.Label(root, textvariable=self.status_var, wraplength=510).pack(anchor="w", pady=(12, 0))
        ttk.Label(root, text="Tryb offline nie działa na serwerach wymagających logowania Microsoft.",
                  foreground="#9b4d00", wraplength=510).pack(anchor="w", pady=(6, 0))

    def refresh_versions(self):
        try:
            self.versions = minecraft_launcher_lib.utils.get_installed_versions(str(self.game_dir)) if minecraft_launcher_lib else []
            names = [v.get("id", "") for v in self.versions if v.get("id")]
            self.version_box["values"] = names
            if names:
                self.version_var.set(names[0])
                self.status_var.set(f"Znaleziono {len(names)} wersji w: {self.game_dir}")
            else:
                self.version_var.set("")
                self.status_var.set(f"Nie znaleziono wersji w: {self.game_dir}")
        except Exception as exc:
            self.status_var.set(f"Błąd skanowania wersji: {exc}")

    def play(self):
        if minecraft_launcher_lib is None:
            messagebox.showerror("Brak biblioteki", "Uruchom build.bat lub zainstaluj: py -m pip install -r requirements.txt")
            return
        version = self.version_var.get().strip()
        username = self.username_var.get().strip()
        key = self.key_var.get().strip()
        if not version:
            messagebox.showerror("Brak wersji", "Wybierz zainstalowaną wersję Minecraft/Fabric.")
            return
        if not safe_username(username):
            messagebox.showerror("Nieprawidłowa nazwa", "Nazwa musi mieć 3–16 znaków: litery, cyfry lub podkreślenie.")
            return
        if not key:
            messagebox.showerror("Brak licencji", "Wpisz klucz licencji Orange Client.")
            return
        # Match the file and JSON field used by LicenseManager.java.
        config_dir = self.game_dir / "config"
        config_dir.mkdir(parents=True, exist_ok=True)
        license_file = config_dir / "orangeclient_license.json"
        try:
            license_file.write_text(json.dumps({"key": key}, ensure_ascii=False), encoding="utf-8")
        except OSError as exc:
            messagebox.showerror("Błąd zapisu", f"Nie można zapisać klucza licencji:\n{exc}")
            return
        self.play_button.configure(state="disabled")
        self.status_var.set("Uruchamianie Minecrafta… klient moda zweryfikuje licencję po starcie.")
        threading.Thread(target=self._launch, args=(version, username), daemon=True).start()

    def _launch(self, version, username):
        try:
            options = {
                "username": username,
                "uuid": "00000000-0000-0000-0000-000000000000",
                "token": "",
                "gameDirectory": str(self.game_dir),
            }
            command = minecraft_launcher_lib.command.get_minecraft_command(
                version, str(self.game_dir), options
            )
            subprocess.Popen(command, cwd=str(self.game_dir))
            self.after(0, lambda: self.status_var.set("Proces gry został uruchomiony. Licencja jest sprawdzana przez moda."))
        except Exception as exc:
            self.after(0, lambda: messagebox.showerror(
                "Nie udało się uruchomić", f"{exc}\n\nSprawdź, czy wybrana wersja jest kompletna i zawiera Fabric."
            ))
            self.after(0, lambda: self.status_var.set("Uruchomienie nie powiodło się."))
        finally:
            self.after(0, lambda: self.play_button.configure(state="normal"))


if __name__ == "__main__":
    if minecraft_launcher_lib is None:
        root = tk.Tk()
        root.withdraw()
        messagebox.showerror("Brak zależności", "Zainstaluj zależności poleceniem: py -m pip install -r requirements.txt")
        root.destroy()
        sys.exit(1)
    Loader().mainloop()
