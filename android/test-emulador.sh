#!/bin/bash
# Prueba la app en un emulador y guarda lo que pase (para encontrar errores).
set -x
OUT=/tmp/out; mkdir -p $OUT
adb install -r nuestro-lugar.apk > $OUT/install.txt 2>&1
adb logcat -c
adb shell am start -W -n com.jon.nuestrolugar/.MainActivity > $OUT/start.txt 2>&1
sleep 12
adb exec-out screencap -p > $OUT/pantalla1.png
# Simula pegar el link y entrar (sin clave real: solo para probar la pantalla web)
adb shell input tap 540 1200 || true
sleep 3
adb shell pidof com.jon.nuestrolugar > $OUT/pid.txt 2>&1 || echo "NO CORRE" > $OUT/pid.txt
adb logcat -d > $OUT/logcat-todo.txt
grep -E "AndroidRuntime|FATAL|nuestrolugar|Exception" $OUT/logcat-todo.txt | head -200 > $OUT/errores.txt
adb shell dumpsys activity services com.jon.nuestrolugar > $OUT/servicios.txt 2>&1
exit 0
