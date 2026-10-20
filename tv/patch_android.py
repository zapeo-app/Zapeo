"""Ajusta el proyecto Android generado por Capacitor para que funcione en Android TV / Google TV."""
import os
import pathlib
import re
import shutil

root = pathlib.Path("android")
manifest = root / "app" / "src" / "main" / "AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")

# 1) Banner del lanzador de TV
text = text.replace("<application", '<application android:banner="@drawable/banner"', 1)

# 2) Declarar que sirve para TV y que no necesita pantalla táctil
features = (
    '    <uses-feature android:name="android.software.leanback" android:required="false" />\n'
    '    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />\n'
)
if "android.software.leanback" not in text:
    text = text.replace("<application", features + "    <application", 1)

# 3) Que aparezca en la lista de apps de la tele
launcher = '<category android:name="android.intent.category.LAUNCHER" />'
leanback = '<category android:name="android.intent.category.LEANBACK_LAUNCHER" />'
if leanback not in text:
    assert launcher in text, "No se encontró la categoría LAUNCHER en el manifiesto"
    text = text.replace(launcher, launcher + "\n                " + leanback, 1)

manifest.write_text(text, encoding="utf-8")

# 4) Copiar el banner
drawable = root / "app" / "src" / "main" / "res" / "drawable"
drawable.mkdir(parents=True, exist_ok=True)
shutil.copy("tv/banner.png", drawable / "banner.png")

# 5) Número de versión creciente, para que cada APK nuevo se pueda instalar encima del anterior
build_number = os.environ.get("BUILD_NUMBER", "1")
gradle = root / "app" / "build.gradle"
g = gradle.read_text(encoding="utf-8")
g = re.sub(r"versionCode\s+\d+", "versionCode " + str(int(build_number) + 1000), g, count=1)  # +1000: siempre mayor que las compilaciones del repo antiguo
g = re.sub(r'versionName\s+"[^"]*"', 'versionName "0.18.' + build_number + '"', g, count=1)

# 6) Firmar SIEMPRE con la misma llave (tv/debug.keystore), para poder actualizar sin desinstalar.
#    Se declara en el propio proyecto: así no depende de dónde busque Gradle la llave en el servidor.
signing = (
    "    signingConfigs {\n"
    "        debug {\n"
    '            storeFile file("../../tv/debug.keystore")\n'
    '            storePassword "android"\n'
    '            keyAlias "androiddebugkey"\n'
    '            keyPassword "android"\n'
    "        }\n"
    "    }\n"
)
if "signingConfigs" not in g:
    assert "\nandroid {\n" in g, "No se encontró el bloque android { } en build.gradle"
    g = g.replace("\nandroid {\n", "\nandroid {\n" + signing, 1)

# 7) Reproductor nativo (ExoPlayer / Media3). Con ZAPEO_NO_EXO=1 se omite (red de seguridad si no compila).
if os.environ.get("ZAPEO_NO_EXO") != "1":
    MEDIA3 = "1.5.1"
    deps = (
        '    implementation "androidx.media3:media3-exoplayer:%s"\n'
        '    implementation "androidx.media3:media3-exoplayer-hls:%s"\n'
    ) % (MEDIA3, MEDIA3)
    if "media3-exoplayer" not in g:
        assert "\ndependencies {\n" in g, "No se encontró el bloque dependencies { } en build.gradle"
        g = g.replace("\ndependencies {\n", "\ndependencies {\n" + deps, 1)
    pkg = root / "app" / "src" / "main" / "java" / "com" / "zapeo" / "player"
    pkg.mkdir(parents=True, exist_ok=True)
    for f in sorted(pathlib.Path("tv").glob("*.java")):
        shutil.copy(f, pkg / f.name)
    (pkg / "MainActivity.java").write_text(
        "package com.zapeo.player;\n\n"
        "import android.os.Bundle;\n"
        "import com.getcapacitor.BridgeActivity;\n\n"
        "public class MainActivity extends BridgeActivity {\n"
        "    @Override\n"
        "    public void onCreate(Bundle savedInstanceState) {\n"
        "        registerPlugin(ZapeoPlayerPlugin.class);\n"
        "        super.onCreate(savedInstanceState);\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )
    # Las direcciones http (la mayoría de listas) deben poder abrirse también desde el código nativo
    mt = manifest.read_text(encoding="utf-8")
    if "usesCleartextTraffic" not in mt:
        mt = mt.replace("<application", '<application android:usesCleartextTraffic="true"', 1)
        manifest.write_text(mt, encoding="utf-8")
    print("ExoPlayer (Media3 %s) añadido" % MEDIA3)
else:
    print("Sin ExoPlayer (modo de seguridad)")

gradle.write_text(g, encoding="utf-8")

print("Proyecto ajustado para Android TV, versión", build_number)
