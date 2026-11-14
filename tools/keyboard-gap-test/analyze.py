"""Looks for the keyboard's white space in a screen recording: full-width bands of flat near-white
(the window or WebView background showing where the page has not drawn). Gboard's own greys stay
below the threshold, and the page (wallpaper, chat) is never flat across the whole width."""
import os
import sys
sys.stdout.reconfigure(encoding="utf-8")

import cv2
import numpy as np

video, out_dir, label = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(out_dir, exist_ok=True)
cap = cv2.VideoCapture(video)
fps = cap.get(cv2.CAP_PROP_FPS) or 30
W, H = 216, 480
MIN_ROWS = int(H * 0.03)  # a band at least 3 % of the screen tall (~70 px on a phone)
bad = []
i = 0
while True:
    ok, frame = cap.read()
    if not ok:
        break
    small = cv2.resize(frame, (W, H), interpolation=cv2.INTER_AREA).astype(np.float32)
    gray = small.mean(axis=2)
    # Ignore the status bar and the navigation bar.
    rows = gray[int(H * 0.05):int(H * 0.96)]
    flat = (rows.std(axis=1) < 1.5) & (rows.mean(axis=1) >= 253)
    run, best = 0, 0
    for f in flat:
        run = run + 1 if f else 0
        best = max(best, run)
    if best >= MIN_ROWS:
        bad.append((i, best))
        if len([b for b in bad]) <= 6:
            cv2.imwrite(os.path.join(out_dir, f'{label}_f{i:04d}.png'), cv2.resize(frame, (360, 800)))
    i += 1
cap.release()
if bad:
    worst = max(b for _, b in bad)
    print(f'FALLA  {label}: {len(bad)} cuadros con espacio blanco (≈{len(bad) / fps * 1000:.0f} ms), '
          f'banda máx {worst * 100 // H} % de la pantalla; primero en el cuadro {bad[0][0]} de {i}')
else:
    print(f'ok     {label}: sin espacio blanco en {i} cuadros')
