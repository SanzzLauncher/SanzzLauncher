# game-files/ — taruh asset game di sini

Folder ini **sengaja kosong di git** (lihat `.gitignore`). Isinya ±1,34 GB / 295 file,
dan file sebesar itu tidak boleh masuk git — mereka di-upload ke **GitHub Release**,
bukan ke commit.

## Struktur yang harus ada

```
game-files/
├── SAMP/          SAMP.ide, TIMECYC.DAT, WEAPON.dat, …
├── anim/          cuts.img, ped.ifp, …
├── audio/         CONFIG/, STREAMS/, …
├── data/          fonts.dat, ShopUVs.dat, maps/, script/mainV1.scm, …
├── fonts/
├── models/        coll/peds.col, effects.fxp, …
├── texdb/         gta3.img (270 MB), gta_int.img, samp.img, gta3/gta3.dxt.dat, …
├── textures/      game.img (270 MB), game.bin (223 MB), multiplayer.img, …
└── stream.ini
```

## Dua path yang paling sering salah

```
texdb/gta3.img          270,39 MB     ← BUKAN models/gta3.img
texdb/gta3/gta3.txt       0,76 MB     ← BUKAN texdb/gta3.txt
```

Kalau dua file ini tidak ada di lokasi yang benar, `checkGameIntegrity` akan
selalu melaporkan "Perlu Setup" apa pun yang terjadi.

## Setelah folder ini terisi

```bash
cd backend
node generate-github-manifest.mjs ../game-files SanzzLauncher SanzzLauncher v1.0.0 ../manifest.json
mv ../manifest-config.json ../config.json
./github-release-upload.sh SanzzLauncher SanzzLauncher v1.0.0 ../game-files 8
cd .. && git add manifest.json config.json && git commit -m "asset v1.0.0" && git push
```

Lengkapnya: `backend/docs/SETUP-BACKEND-GRATIS.md`
