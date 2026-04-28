package com.example.kutil6_yas;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import androidx.activity.result.ActivityResultLauncher;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class FileUtil {
    public Uri[] selUris;
    public String[] selNames;
    public Context context;

    public FileUtil(Context c) {
        this.selUris = new Uri[0];
        this.selNames = new String[0];
        this.context = c;
    }

    // =====================================================================
    // [인터페이스] 콜백 및 전략 패턴 정의 (모듈 내부에 통합)
    // =====================================================================

    /** 범용 폴더 재귀 탐색을 위한 Visitor 콜백 */
    public interface FileVisitor {
        void onDirectory(DocumentFile dir, String relativePath) throws Exception;
        void onFile(DocumentFile file, String relativePath) throws Exception;
    }

    /** 확장 가능한 압축 엔진(ZIP, TAR 등)을 위한 전략 인터페이스 */
    public interface IArchiveWriter {
        void open(OutputStream os) throws Exception;
        void addFile(String entryName, InputStream is) throws Exception;
        void close() throws Exception;
    }

    // =====================================================================
    // 0. 파일/폴더 선택 창 호출 및 결과 처리
    // =====================================================================

    public void SelectFile(ActivityResultLauncher<Intent> launcher, boolean multi) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (multi) { intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); }
        launcher.launch(intent);
    }

    public void SelectFolder(ActivityResultLauncher<Intent> launcher) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        launcher.launch(intent);
    }

    public void HandleSelectionFile(Intent data) {
        if (data.getClipData() != null) {
            int length = data.getClipData().getItemCount();
            this.selUris = new Uri[length];
            this.selNames = new String[length];
            for (int i = 0; i < length; i++) {
                this.selUris[i] = data.getClipData().getItemAt(i).getUri();
                this.selNames[i] = getFileName(this.selUris[i]);
            }
        } else if (data.getData() != null) {
            this.selUris = new Uri[1];
            this.selNames = new String[1];
            this.selUris[0] = data.getData();
            this.selNames[0] = getFileName(this.selUris[0]);
        } else {
            this.selUris = new Uri[0];
            this.selNames = new String[0];
        }
    }

    public void HandleSelectionFolder(Intent data) {
        if (data.getData() != null) {
            this.selUris = new Uri[1];
            this.selNames = new String[1];
            this.selUris[0] = data.getData();
            this.selNames[0] = getFolderName(this.selUris[0]);
            if (!this.selNames[0].endsWith("/")) { this.selNames[0] += "/"; }
        } else {
            this.selUris = new Uri[0];
            this.selNames = new String[0];
        }
    }

    public String PrintSelection() {
        StringBuilder ret = new StringBuilder();
        for (int i = 0; i < this.selUris.length; i++) {
            if (DocumentsContract.isTreeUri(this.selUris[i])) { ret.append("Dir: ").append(this.selNames[i]).append("/\n"); }
            else { ret.append("File: ").append(this.selNames[i]).append("\n"); }
        }
        return ret.toString();
    }

    // =====================================================================
    // 1. 코어 스트림 제어 (내부/외부 완벽 통합) - 구 StorageIO 로직
    // =====================================================================

    public InputStream openRead(Uri fileUri) throws IOException {
        InputStream is = context.getContentResolver().openInputStream(fileUri);
        if (is == null) throw new IOException("스트림을 열 수 없습니다: " + fileUri);
        return is;
    }

    public OutputStream openWrite(Uri fileUri) throws IOException {
        OutputStream os = context.getContentResolver().openOutputStream(fileUri);
        if (os == null) throw new IOException("스트림을 열 수 없습니다: " + fileUri);
        return os;
    }

    // =====================================================================
    // 2. 선택된 외부 객체(URI) 다루기
    // =====================================================================

    public InputStream OpenReadSelected(int index) throws Exception {
        if (index < 0 || index >= selUris.length) throw new Exception("잘못된 인덱스입니다.");
        return openRead(selUris[index]);
    }

    public OutputStream OpenWriteSelected(int index) throws Exception {
        if (index < 0 || index >= selUris.length) throw new Exception("잘못된 인덱스입니다.");
        return openWrite(selUris[index]);
    }

    public void CreateFolderInSelected(int folderIndex, String newFolderName) throws Exception {
        if (folderIndex < 0 || folderIndex >= selUris.length) throw new Exception("잘못된 인덱스입니다.");
        DocumentFile dir = DocumentFile.fromTreeUri(context, selUris[folderIndex]);
        if (dir != null && dir.isDirectory()) dir.createDirectory(newFolderName);
    }

    public OutputStream CreateFileInSelected(int folderIndex, String fileName, String mimeType) throws Exception {
        if (folderIndex < 0 || folderIndex >= selUris.length) throw new Exception("잘못된 인덱스입니다.");
        DocumentFile dir = DocumentFile.fromTreeUri(context, selUris[folderIndex]);
        if (dir == null || !dir.isDirectory()) throw new Exception("유효한 폴더가 아닙니다.");
        
        DocumentFile newFile = dir.createFile(mimeType, fileName);
        if (newFile == null) throw new Exception("파일 생성 실패: " + fileName);
        return openWrite(newFile.getUri());
    }

    // =====================================================================
    // 3. 재귀(Recursive) 범용 탐색 및 응용
    // =====================================================================

    /** [마스터 재귀 함수] 선택한 폴더를 탐색하며 Visitor에게 이벤트를 전달합니다. */
    public void WalkSelectedFolder(int folderIndex, FileVisitor visitor) throws Exception {
        if (folderIndex < 0 || folderIndex >= selUris.length) throw new Exception("잘못된 인덱스입니다.");
        DocumentFile sourceDir = DocumentFile.fromTreeUri(context, selUris[folderIndex]);
        if (sourceDir == null || !sourceDir.isDirectory()) throw new Exception("유효한 폴더가 아닙니다.");
        walkRecursive(sourceDir, "", visitor);
    }

    private void walkRecursive(DocumentFile dir, String basePath, FileVisitor visitor) throws Exception {
        for (DocumentFile child : dir.listFiles()) {
            if (child.isDirectory()) {
                String newBasePath = basePath + child.getName() + "/";
                visitor.onDirectory(child, newBasePath);
                walkRecursive(child, newBasePath, visitor);
            } else {
                visitor.onFile(child, basePath + child.getName());
            }
        }
    }

    /** [응용] 재귀적으로 선택한 폴더 내부 비우기 */
    public void EmptySelectedFolder(int folderIndex) throws Exception {
        WalkSelectedFolder(folderIndex, new FileVisitor() {
            @Override
            public void onDirectory(DocumentFile dir, String relativePath) { dir.delete(); }
            @Override
            public void onFile(DocumentFile file, String relativePath) { file.delete(); }
        });
    }

    /** [응용] 재귀적으로 폴더 전체 압축 (ZIP, TAR 엔진 주입) */
    public void ArchiveSelectedFolder(int folderIndex, OutputStream os, IArchiveWriter engine) throws Exception {
        engine.open(os); // 압축 엔진 시작
        WalkSelectedFolder(folderIndex, new FileVisitor() {
            @Override
            public void onDirectory(DocumentFile dir, String relativePath) {
                // 필요시 엔진에 빈 폴더 생성 로직 추가 가능
            }
            @Override
            public void onFile(DocumentFile file, String relativePath) throws Exception {
                InputStream is = openRead(file.getUri());
                engine.addFile(relativePath, is);
                is.close();
            }
        });
        engine.close(); // 압축 엔진 종료
    }

    // =====================================================================
    // 4. 앱 내부 저장소(Local) 캡슐화 제어
    // =====================================================================

    public File GetLocalFile(String name) { return new File(this.context.getFilesDir(), name); }

    public InputStream OpenReadLocal(String name) throws Exception {
        return new FileInputStream(GetLocalFile(name));
    }

    public OutputStream OpenWriteLocal(String name) throws Exception {
        return new FileOutputStream(GetLocalFile(name));
    }

    // =====================================================================
    // 5. 다운로드 폴더 자동 쓰기 (MediaStore 방식 유지)
    // =====================================================================

    public Uri GetDownloadsFile(String name) {
        ContentValues values = new ContentValues();
        String mimeType = null;
        int dotpos = name.lastIndexOf('.');
        if (dotpos != -1 && dotpos != name.length() - 1) {
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dotpos + 1).toLowerCase());
        }
        if (mimeType == null) { mimeType = "application/octet-stream"; }
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        return this.context.getContentResolver().insert(collection, values);
    }

    public OutputStream OpenWriteDownloads(String name) throws Exception {
        Uri newFileUri = GetDownloadsFile(name);
        if (newFileUri == null) throw new Exception("다운로드 폴더에 파일 생성 실패");
        return openWrite(newFileUri);
    }

    // =====================================================================
    // 6. 유틸리티 (이름, 크기 가져오기)
    // =====================================================================

    private String getFileName(Uri uri) {
        String name = null;
        try (Cursor cursor = this.context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx != -1) { name = cursor.getString(idx); }
            }
        }
        return name;
    }

    private String getFolderName(Uri uri) {
        String docId = DocumentsContract.getTreeDocumentId(uri);
        String[] split = docId.split(":");
        if (split.length > 1) { docId = split[1]; }
        if (docId.endsWith("/")) { docId = docId.substring(0, docId.length() - 1); }
        split = docId.split("/");
        return split[split.length - 1];
    }

    public long GetFileSize(File f) { return f.length(); }

    public long GetFileSize(Uri f) {
        long fileSize = -1;
        try (Cursor cursor = this.context.getContentResolver().query(
                f, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex != -1) { fileSize = cursor.getLong(sizeIndex); }
            }
        }
        return fileSize;
    }
}
