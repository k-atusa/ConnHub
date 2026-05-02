# ConnHub

Connection Hub R4

> ConnHub is simple text/file sharing hub for local network.

## Usage

- Share text by typing in text area.
- Upload file to share inside local network.
- Text and file list are synced every 3 seconds.

## Build Executable

desktop version
```bash
go mod init example.com
go mod tidy
go build -ldflags="-s -w" -trimpath server.go
```

android version
```bash
gradlew.bat clean
gradlew.bat [assembleRelease|assembleDebug]
cd android/app/build/outputs/apk/debug
```

# Android Project

```python
mainfests/
    AndroidManifest.xml #set main activity, service, permissions
java/
    com.example.package/
        code.java #codes here
assets/ #app - new - folder - asset folder
    data.html #datas that can be used by app
res/
    drawable/ #drawable - new - vector asset - search, add
        icon.xml #icon or component xml
    layout/
        mainview.xml # screen xml
    mipmap/ #res - new - image asset - add foreground, background image
    values/
        themes/ #button, background colors
        colors.xml
        strings.xml
    xml/
        config.xml #config for some apps
build.gradle.kts #file - project structure - dependency - add, version info
```

- Align and Sign Release build with jks keyfile
- There is two memory limit: Device limit and VM limit
    - Modern Android device RAM is 6~16 GB
    - But memory that one process can use is limited as VM memory, usually 256 MB
    - Use independent process to bypass this limit
- Declare largeheap in AndroidManifest to extend VM limit to 512 MB
