#!/bin/bash
# Prueba la app en un emulador y guarda lo que pase (para encontrar errores).
set -x
OUT=/tmp/out; mkdir -p $OUT
APP1=com.jon.nuestrolugar; APP2=com.jon.nuestrolugar.jon
FALLAS=""
falla(){ echo "FALLA: $1"; FALLAS="$FALLAS$1"$'\n'; }
viva(){ [ -n "$(adb shell pidof "$1" 2>/dev/null | tr -d '\r ')" ]; }
tap_res(){ # toca el centro del elemento con ese texto/clase
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml $OUT/u.xml >/dev/null 2>&1
  b=$(grep -o "$1[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" $OUT/u.xml | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | tr -d '[]' | tr ',' ' ')
  set -- $b; [ -n "$1" ] && adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
adb install -r nuestro-lugar.apk > $OUT/install.txt 2>&1
grep -q Success $OUT/install.txt || falla "no se instalo $APP1"
adb shell pm grant com.jon.nuestrolugar android.permission.POST_NOTIFICATIONS
adb logcat -c
adb shell am start -W -n com.jon.nuestrolugar/.MainActivity > $OUT/start.txt 2>&1
grep -qE "Error|Exception" $OUT/start.txt && falla "no abrio $APP1"
sleep 8
viva $APP1 || falla "$APP1 no esta corriendo tras abrir"
adb exec-out screencap -p > $OUT/1-pegar-link.png
tap_res 'text="Soy Jon"'
sleep 1
tap_res 'class="android.widget.EditText"'
sleep 1
adb shell input text "https://jonatan-montiel.github.io/ale-meses/#k=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
sleep 1
adb shell input keyevent 66
sleep 15
viva $APP1 || falla "$APP1 murio tras cargar la pagina"
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
# Segunda app (Jon): instalar, abrir (por launcher, sin depender del nombre de la actividad) y ver que siga viva
adb install -r nuestro-lugar-jon.apk > $OUT/install-jon.txt 2>&1
grep -q Success $OUT/install-jon.txt || falla "no se instalo $APP2"
adb shell pm grant $APP2 android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell monkey -p $APP2 -c android.intent.category.LAUNCHER 1 > $OUT/start-jon.txt 2>&1
sleep 10
viva $APP2 || falla "$APP2 no esta corriendo tras abrir"
adb exec-out screencap -p > $OUT/4-app-jon.png
# Fuerza una vuelta del job (revisa avisos y el actualizador). En el emulador el actualizador solo registra: no descarga ni instala.
timeout 30 adb shell cmd jobscheduler run -f $APP1 1203 > $OUT/job-run.txt 2>&1 || true
sleep 8
viva $APP1 || falla "$APP1 murio tras la vuelta del job"
adb logcat -d > $OUT/logcat-todo.txt
grep "NLUpd" $OUT/logcat-todo.txt > $OUT/updater.txt
# durante la prueba nadie debe intentar instalar una actualizacion
grep -qE "NLUpd.*(commit de la sesi|instalando v|descargado y verificado)" $OUT/logcat-todo.txt && falla "el actualizador intento instalar durante la prueba (ver updater.txt)"
# errores.txt: solo lo de nuestros paquetes (es lo unico que se sube como resumen)
{ grep -A12 "FATAL EXCEPTION" $OUT/logcat-todo.txt | grep -E "FATAL|Process: com\.jon\.nuestrolugar|^.*at com\.jon\."; grep -E "nuestrolugar|NLUpd" $OUT/logcat-todo.txt | grep -v "WindowManager"; } | head -200 > $OUT/errores.txt
# un FATAL EXCEPTION cuenta solo si el proceso es de nuestros paquetes
if grep -A6 "FATAL EXCEPTION" $OUT/logcat-todo.txt | grep -qE "Process: com\.jon\.nuestrolugar(\.jon)?,"; then
  falla "FATAL EXCEPTION en com.jon.nuestrolugar* (ver errores.txt)"
fi
if [ -n "$FALLAS" ]; then
  { echo FAIL; printf %s "$FALLAS"; } > $OUT/resultado.txt
  cat $OUT/resultado.txt
  exit 1
fi
echo PASS > $OUT/resultado.txt
exit 0
