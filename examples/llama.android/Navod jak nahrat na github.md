

📖 Návod: Jak nahrát velký Android/C++ projekt na GitHub (1:1)
Tento postup zajistí, že na GitHubu bude identická kopie tvého lokálního disku, včetně velkých statických knihoven (.a), které přesahují limit 100 MB.

1. Příprava lokálního adresáře
   Před nahráváním je nutné odstranit starou historii Gitu a zajistit, aby žádná pravidla neblokovala tvé soubory.
2. 

Otevři terminál v kořeni projektu ().
2.
Smaž starý Git:
cmd /c "rd /s /q .git"
3.
Vyčisti pravidla: Ujisti se, že soubor  .gitignore je prázdný, nebo v něm nejsou žádné zákazy pro .a, .so nebo libs složky.
2. Inicializace a nastavení Git LFS
Protože GitHub nepovoluje soubory nad 100 MB, musíš použít Git LFS (Large File Storage).
1.
Inicializuj projekt:
git init
git lfs install
2.
Nastav sledování velkých souborů: (Tímto LFS pohlídá binárky a obejde limit 100 MB).

git lfs track "\*.onnx"

git lfs track "\*.comp.cpp"

git lfs track "\*.a"
git lfs track "*.a"
git lfs track "*.so"

*git lfs track "\**.so"
git lfs track "*.onnx"
git lfs track "*.tflite"
git lfs track "*.task"
git lfs track "*.gguf"
git lfs track "\*.aar"
3.
Přidej konfigurační soubor:
git add .gitattributes
3. Commit a nahrání (Upload)
Přidej úplně všechno (1:1):
git add .
git commit -m "Initial 1:1 upload: Full project state"
2.
Propoj se s GitHubem:
git remote add origin https://github.com/KrakeCZ/AIchat-Android-Local-AI-Agent-.git
git branch -M main
3.
Vystřel to na GitHub:
git push -u origin main --force
4. Aktivace "Download ZIP" na GitHubu
Ve výchozím stavu GitHub do ZIPu nedává velké soubory z LFS. Aby tlačítko "Download ZIP" fungovalo pro tvých 1.8 GB, musíš to povolit:
1.
Jdi na GitHub do svého repozitáře.
2.
Klikni na Settings -> General.
3.
Sroluj dolů k sekci Archives.
4.
Zaškrtni: "Include Git LFS objects in archives".
💡 Jak projekt správně stáhnout (Clone)
Pokud ty nebo někdo jiný projekt stahuje, nejjistější cesta pro zachování funkčnosti je:
1.
Mít nainstalovaný Git a Git LFS.
2.
Spustit:
git clone https://github.com/KrakeCZ/AIchat-Android-Local-AI-Agent-.git
cd AIchat-Android-Local-AI-Agent-
git lfs pull
Tento postup zaručuje, že projekt bude po stažení okamžitě zkompilovatelný a kompletní. 😊
Máš to tam, projekt je venku a funkční! Pokud budeš chtít příště něco měnit, už jen stačí klasické git add, git commit a git push.















\# 1. Přidej .so soubory do sledování LFS (ty tam minule chyběly)

git lfs track "\*.so"

git lfs track "\*.a"



\# 2. Obnov index, aby Git pochopil, že ty velké soubory patří do LFS

\# (Tento příkaz "vytáhne" soubory z běžné historie a hodí je do LFS)

git add .gitattributes

git add .



\# 3. Oprav poslední commit

git commit --amend --no-edit



\# 4. Zkus to znovu nahrat

git push origin main --force

