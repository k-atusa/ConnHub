// test803 : ConnHub desktop
package main

import (
	"archive/zip"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// global storage
var text Text
var files Files
var tempDir string = "./temp"

type Text struct {
	data string
	ts   int64
	lock sync.RWMutex
}

type FileEntry struct {
	Name string `json:"name"` // base64url-encoded filename (from JS)
	Size int64  `json:"size"` // file size in bytes
}

type Files struct {
	data []FileEntry
	ts   int64
	lock sync.RWMutex
}

func main() {
	// parse args
	port := 8000
	delFiles := false
	showIPv6 := false
	for _, arg := range os.Args[1:] {
		if arg == "-del" {
			delFiles = true
		} else if arg == "-ipv6" {
			showIPv6 = true
		} else if n, err := strconv.Atoi(arg); err == nil && n > 0 {
			port = n
		}
	}

	// check if port is already in use
	svcIP := fmt.Sprintf("0.0.0.0:%d", port)
	chk, err := net.Listen("tcp", svcIP)
	if err != nil {
		log.Println(svcIP + " is already in use")
		return
	}
	chk.Close()

	// setup temp dir
	if _, err := os.Stat(tempDir); os.IsNotExist(err) {
		os.MkdirAll(tempDir, 0755)
	} else if delFiles {
		os.RemoveAll(tempDir)
		os.MkdirAll(tempDir, 0755)
	}

	// initialize global storage
	files.data = make([]FileEntry, 0)
	if !delFiles {
		loadExistingFiles()
	}
	files.ts = time.Now().UnixMilli()
	text.ts = time.Now().UnixMilli()

	// API endpoints
	server := http.FileServer(http.Dir("./"))
	http.Handle("/", server)
	http.HandleFunc("/api/state", handleState)
	http.HandleFunc("/api/text", handleText)
	http.HandleFunc("/api/files/upload", handleFileUpload)
	http.HandleFunc("/api/files/download/", handleFileDownload)
	http.HandleFunc("/api/files/download-all", handleDownloadAll)
	http.HandleFunc("/api/files/delete/", handleFileDelete)
	http.HandleFunc("/api/files/delete-all", handleFileDeleteAll)
	log.Println("server initialized")

	// print local IPs
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		fmt.Println(err)
	}
	for _, addr := range addrs {
		if ipnet, ok := addr.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
			if ipnet.IP.To4() != nil {
				log.Printf("http://%s:%d", ipnet.IP.String(), port)
			} else if showIPv6 {
				log.Printf("http://[%s]:%d", ipnet.IP.String(), port)
			}
		}
	}

	// support both IPv4 and IPv6
	if err := http.ListenAndServe(fmt.Sprintf(":%d", port), nil); err != nil {
		log.Println(err)
	}
}

// load existing files from temp dir
func loadExistingFiles() {
	entries, err := os.ReadDir(tempDir)
	if err != nil {
		log.Println("Failed to read temp dir: ", err)
		return
	}

	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		files.data = append(files.data, FileEntry{
			Name: entry.Name(),
			Size: info.Size(),
		})
	}
	log.Printf("loaded %d existing files\n", len(files.data))
}

// state sync check
func handleState(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	q := r.URL.Query()
	clientTextTs, _ := strconv.ParseInt(q.Get("text_ts"), 10, 64)
	clientFilesTs, _ := strconv.ParseInt(q.Get("files_ts"), 10, 64)
	resp := make(map[string]interface{})

	// add text if updated
	text.lock.RLock()
	if text.ts > clientTextTs {
		resp["text"] = map[string]interface{}{
			"updated": true,
			"data":    text.data,
			"ts":      text.ts,
		}
	} else {
		resp["text"] = map[string]interface{}{"updated": false}
	}
	text.lock.RUnlock()

	// add files if updated
	files.lock.RLock()
	if files.ts > clientFilesTs {
		resp["files"] = map[string]interface{}{
			"updated": true,
			"data":    files.data,
			"ts":      files.ts,
		}
	} else {
		resp["files"] = map[string]interface{}{"updated": false}
	}
	files.lock.RUnlock()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

// text set
func handleText(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// read body text
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "Error reading body", http.StatusBadRequest)
		return
	}

	// update text
	text.lock.Lock()
	text.data = string(body)
	text.ts = time.Now().UnixMilli()
	currentTs := text.ts
	text.lock.Unlock()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]int64{"ts": currentTs})
}

// file upload, filename is encoded Base64 string
func handleFileUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	files.lock.Lock()
	defer files.lock.Unlock()

	r.ParseMultipartForm(128 * 1048576) // load 128MiB into memory
	file, _, err := r.FormFile("file")
	if err != nil {
		http.Error(w, "Error reading file", http.StatusBadRequest)
		return
	}
	defer file.Close()

	// JS provides the base64url-encoded name
	encodedName := strings.TrimSpace(r.FormValue("filename"))
	if encodedName == "" {
		http.Error(w, "Missing filename", http.StatusBadRequest)
		return
	}
	savePath := filepath.Join(tempDir, encodedName) // Use encoded name directly as the disk filename
	if _, err := os.Stat(savePath); err == nil {
		os.Remove(savePath)
	}

	// save file
	dst, err := os.Create(savePath)
	if err != nil {
		http.Error(w, "Error saving file", http.StatusInternalServerError)
		return
	}
	defer dst.Close()
	buf := make([]byte, 1048576) // 1 MB chunks
	written, err := io.CopyBuffer(dst, file, buf)
	if err != nil {
		http.Error(w, "Error saving file", http.StatusInternalServerError)
		return
	}

	// update files
	newList := make([]FileEntry, 0)
	for _, f := range files.data {
		if f.Name != encodedName {
			newList = append(newList, f)
		}
	}
	files.data = append(newList, FileEntry{Name: encodedName, Size: written})
	files.ts = time.Now().UnixMilli()
	w.WriteHeader(http.StatusOK)
}

// file download
func handleFileDownload(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// JS provides the base64url-encoded name
	encodedName := filepath.Base(r.URL.Path)
	filePath := filepath.Join(tempDir, encodedName)
	file, err := os.Open(filePath)
	if err != nil {
		http.Error(w, "File not found", http.StatusNotFound)
		return
	}
	defer file.Close()

	// get real name
	realName := r.URL.Query().Get("name")
	if realName == "" {
		realName = encodedName
	}
	w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+url.QueryEscape(realName))
	w.Header().Set("Content-Type", "application/octet-stream")

	// send file
	buf := make([]byte, 65536)
	for {
		n, err := file.Read(buf)
		if n > 0 {
			w.Write(buf[:n])
			w.(http.Flusher).Flush()
		}
		if err != nil {
			if err == io.EOF {
				break
			}
			return
		}
	}
}

// download all by zip
func handleDownloadAll(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// check files
	files.lock.RLock()
	defer files.lock.RUnlock()
	if len(files.data) == 0 {
		http.Error(w, "No files to download", http.StatusNotFound)
		return
	}

	// set header as zip
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", `attachment; filename="files.zip"`)

	zw := zip.NewWriter(w)
	defer zw.Close()
	for _, f := range files.data {
		// restore name from base64url
		originalName := f.Name
		decodedBytes, err := base64.RawURLEncoding.DecodeString(f.Name)
		if err == nil {
			originalName = string(decodedBytes)
		}

		// open file
		diskPath := filepath.Join(tempDir, f.Name)
		file, err := os.Open(diskPath)
		if err != nil {
			continue
		}

		// add file to zip
		fw, err := zw.Create(originalName)
		if err != nil {
			file.Close()
			continue
		}

		// copy file to zip stream
		io.Copy(fw, file)
		file.Close()
	}
}

// file delete
func handleFileDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != "DELETE" {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	files.lock.Lock()
	defer files.lock.Unlock()

	// JS provides the base64url-encoded name
	encodedName := filepath.Base(r.URL.Path)
	newList := make([]FileEntry, 0)
	for _, f := range files.data {
		if f.Name != encodedName {
			newList = append(newList, f)
		}
	}
	files.data = newList
	files.ts = time.Now().UnixMilli()

	os.Remove(filepath.Join(tempDir, encodedName))
	w.WriteHeader(http.StatusOK)
}

// delete all files
func handleFileDeleteAll(w http.ResponseWriter, r *http.Request) {
	if r.Method != "DELETE" {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	files.lock.Lock()
	defer files.lock.Unlock()

	// remove files
	for _, f := range files.data {
		os.Remove(filepath.Join(tempDir, f.Name))
	}
	files.data = make([]FileEntry, 0)
	files.ts = time.Now().UnixMilli()
	w.WriteHeader(http.StatusOK)
}
