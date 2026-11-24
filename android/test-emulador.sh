#!/bin/bash
# Prueba la app en un emulador y guarda lo que pase (para encontrar errores).
set -x
OUT=/tmp/out; mkdir -p $OUT
tap_res(){ # toca el centro del elemento con ese texto/clase
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml $OUT/u.xml >/dev/null 2>&1
  b=$(grep -o "$1[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" $OUT/u.xml | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | tr -d '[]' | tr ',' ' ')
  set -- $b; [ -n "$1" ] && adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
adb install -r nuestro-lugar.apk > $OUT/install.txt 2>&1
adb shell pm grant com.jon.nuestrolugar android.permission.POST_NOTIFICATIONS
adb logcat -c
adb shell am start -W -n com.jon.nuestrolugar/.MainActivity > $OUT/start.txt 2>&1
sleep 8
adb exec-out screencap -p > $OUT/1-pegar-link.png
tap_res 'text="Soy Jon"'
sleep 1
tap_res 'class="android.widget.EditText"'
sleep 1
adb shell input text "https://jonatan-montiel.github.io/ale-meses/#k=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
sleep 1
adb shell input keyevent 66
sleep 15
adb exec-out screencap -p > $OUT/2-pagina.png
sleep 5
adb exec-out screencap -p > $OUT/3-pagina-borde.png
adb shell dumpsys window windows | grep -E "mCurrentFocus|statusBar|Window #" | head -20 > $OUT/ventana.txt 2>&1
adb shell uiautomator dump /sdcard/u2.xml >/dev/null 2>&1; adb pull /sdcard/u2.xml $OUT/u2.xml >/dev/null 2>&1
# teclear el código del candado de prueba no aplica (clave falsa): solo vemos que cargue la página
adb shell input keyevent 4
sleep 2
adb shell pidof com.jon.nuestrolugar > $OUT/pid.txt 2>&1 || echo "NO CORRE" > $OUT/pid.txt
adb shell dumpsys jobscheduler com.jon.nuestrolugar | head -40 > $OUT/jobs.txt 2>&1
adb logcat -d > $OUT/logcat-todo.txt
grep "NLUpd" $OUT/logcat-todo.txt > $OUT/updater.txt
grep -E "AndroidRuntime|FATAL|nuestrolugar|chromium|Console" $OUT/logcat-todo.txt | grep -v "WindowManager" | head -200 > $OUT/errores.txt
exit 0
