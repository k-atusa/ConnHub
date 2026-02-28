# ConnHub v1.0.0

Connection Hub

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
