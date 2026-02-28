// test803 : ConnHub desktop
package main

import (
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"
)

// global storage
var text Text
var files Files
var tempDir string = "./temp"

type Text struct {
	data string
	ts   int64 // last updated timestamp (ms)
	lock sync.RWMutex
}

type Files struct {
	data []string
	ts   int64 // last updated timestamp (ms)
	lock sync.RWMutex
}

func main() {
	// get port number
	port := 0
	if len(os.Args) > 1 {
		port, _ = strconv.Atoi(os.Args[1])
	}
	if port == 0 {
		port = 8000 // default port
	}

	// check if port is already in use
	chk, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", port))
	if err != nil {
		log.Println("port in use")
		return
	}
	chk.Close()

	// clear local files
	os.RemoveAll(tempDir)
	os.MkdirAll(tempDir, 0755)
	defer os.RemoveAll(tempDir)
	files.data = make([]string, 0)
	files.ts = time.Now().UnixMilli()
	text.ts = time.Now().UnixMilli()

	// API endpoints
	server := http.FileServer(http.Dir("./"))
	http.Handle("/", server)
	http.HandleFunc("/api/state", handleState)
	http.HandleFunc("/api/text", handleText)
	http.HandleFunc("/api/files/upload", handleFileUpload)
	http.HandleFunc("/api/files/download/", handleFileDownload)
	http.HandleFunc("/api/files/delete/", handleFileDelete)
	log.Println("server initialized")

	// get local ip of server
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		fmt.Println(err)
	}
	for _, addr := range addrs {
		if ipnet, ok := addr.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
			if ipnet.IP.To4() != nil {
				log.Printf("IP %s:%d", ipnet.IP.String(), port)
			}
		}
	}

	// get connection
	if err := http.ListenAndServe(fmt.Sprintf("0.0.0.0:%d", port), nil); err != nil {
		log.Println(err)
	}
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

	// check text
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

	// check files
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

	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "Error reading body", http.StatusBadRequest)
		return
	}

	text.lock.Lock()
	text.data = string(body)
	text.ts = time.Now().UnixMilli()
	currentTs := text.ts
	text.lock.Unlock()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]int64{"ts": currentTs})
}

// file upload
func handleFileUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	files.lock.Lock()
	defer files.lock.Unlock()

	// get file format
	r.ParseMultipartForm(10 << 30) // max size 10gb
	file, header, err := r.FormFile("file")
	if err != nil {
		http.Error(w, "Error reading file", http.StatusBadRequest)
		return
	}
	defer file.Close()
	baseName := filepath.Base(header.Filename)
	encodedName := hex.EncodeToString([]byte(baseName))
	savePath := filepath.Join(tempDir, encodedName)

	// Remove existing file if it exists
	if _, err := os.Stat(savePath); err == nil {
		if err := os.Remove(savePath); err != nil {
			http.Error(w, "Error replacing existing file", http.StatusInternalServerError)
			return
		}
	}

	// Save new file
	dst, err := os.Create(savePath)
	if err != nil {
		http.Error(w, "Error saving file", http.StatusInternalServerError)
		return
	}
	defer dst.Close()

	// Copy file in chunks
	buf := make([]byte, 1048576) // 1MB chunks
	if _, err := io.CopyBuffer(dst, file, buf); err != nil {
		http.Error(w, "Error saving file", http.StatusInternalServerError)
		return
	}

	// Update file list
	newFiles := make([]string, 0)
	for _, f := range files.data {
		if f != baseName {
			newFiles = append(newFiles, f)
		}
	}
	files.data = append(newFiles, baseName) // Add new file
	files.ts = time.Now().UnixMilli()       // Update timestamp
	w.WriteHeader(http.StatusOK)
}

// file download
func handleFileDownload(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// get file name & path
	filename := filepath.Base(r.URL.Path)
	encodedName := hex.EncodeToString([]byte(filename))
	filePath := filepath.Join(tempDir, encodedName)
	file, err := os.Open(filePath)
	if err != nil {
		http.Error(w, "File not found", http.StatusNotFound)
		return
	}
	defer file.Close()

	// set download mode
	w.Header().Set("Content-Disposition", "attachment; filename="+filename)
	w.Header().Set("Content-Type", "application/octet-stream")

	// buffered sending
	buf := make([]byte, 65536)
	for {
		n, err := file.Read(buf)
		if err != nil {
			if err == io.EOF {
				break
			}
			http.Error(w, "Error reading file", http.StatusInternalServerError)
			return
		}
		w.Write(buf[:n])
		w.(http.Flusher).Flush()
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

	// Remove from file list
	filename := filepath.Base(r.URL.Path)
	newFiles := make([]string, 0)
	for _, f := range files.data {
		if f != filename {
			newFiles = append(newFiles, f)
		}
	}
	files.data = newFiles
	files.ts = time.Now().UnixMilli() // Update timestamp

	// Delete file
	encodedName := hex.EncodeToString([]byte(filename))
	os.Remove(filepath.Join(tempDir, encodedName))
}
