package com.camscan.app.data.local.dao;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.camscan.app.data.local.entity.PageEntity;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class PageDao_Impl implements PageDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<PageEntity> __insertionAdapterOfPageEntity;

  private final EntityDeletionOrUpdateAdapter<PageEntity> __updateAdapterOfPageEntity;

  private final SharedSQLiteStatement __preparedStmtOfDeletePage;

  private final SharedSQLiteStatement __preparedStmtOfDeletePagesForDocument;

  public PageDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfPageEntity = new EntityInsertionAdapter<PageEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `pages` (`id`,`documentId`,`pageIndex`,`originalImagePath`,`processedImagePath`,`filterMode`,`rotationDegrees`,`cropCornersJson`,`ocrText`) VALUES (?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final PageEntity entity) {
        statement.bindString(1, entity.getId());
        statement.bindString(2, entity.getDocumentId());
        statement.bindLong(3, entity.getPageIndex());
        statement.bindString(4, entity.getOriginalImagePath());
        statement.bindString(5, entity.getProcessedImagePath());
        statement.bindString(6, entity.getFilterMode());
        statement.bindLong(7, entity.getRotationDegrees());
        if (entity.getCropCornersJson() == null) {
          statement.bindNull(8);
        } else {
          statement.bindString(8, entity.getCropCornersJson());
        }
        if (entity.getOcrText() == null) {
          statement.bindNull(9);
        } else {
          statement.bindString(9, entity.getOcrText());
        }
      }
    };
    this.__updateAdapterOfPageEntity = new EntityDeletionOrUpdateAdapter<PageEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `pages` SET `id` = ?,`documentId` = ?,`pageIndex` = ?,`originalImagePath` = ?,`processedImagePath` = ?,`filterMode` = ?,`rotationDegrees` = ?,`cropCornersJson` = ?,`ocrText` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final PageEntity entity) {
        statement.bindString(1, entity.getId());
        statement.bindString(2, entity.getDocumentId());
        statement.bindLong(3, entity.getPageIndex());
        statement.bindString(4, entity.getOriginalImagePath());
        statement.bindString(5, entity.getProcessedImagePath());
        statement.bindString(6, entity.getFilterMode());
        statement.bindLong(7, entity.getRotationDegrees());
        if (entity.getCropCornersJson() == null) {
          statement.bindNull(8);
        } else {
          statement.bindString(8, entity.getCropCornersJson());
        }
        if (entity.getOcrText() == null) {
          statement.bindNull(9);
        } else {
          statement.bindString(9, entity.getOcrText());
        }
        statement.bindString(10, entity.getId());
      }
    };
    this.__preparedStmtOfDeletePage = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM pages WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeletePagesForDocument = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM pages WHERE documentId = ?";
        return _query;
      }
    };
  }

  @Override
  public Object insertPage(final PageEntity page, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfPageEntity.insert(page);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object insertPages(final List<PageEntity> pages,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfPageEntity.insert(pages);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object updatePage(final PageEntity page, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfPageEntity.handle(page);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deletePage(final String id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeletePage.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeletePage.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deletePagesForDocument(final String documentId,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeletePagesForDocument.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, documentId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeletePagesForDocument.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<PageEntity>> getPagesForDocumentFlow(final String documentId) {
    final String _sql = "SELECT * FROM pages WHERE documentId = ? ORDER BY pageIndex ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, documentId);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"pages"}, new Callable<List<PageEntity>>() {
      @Override
      @NonNull
      public List<PageEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfDocumentId = CursorUtil.getColumnIndexOrThrow(_cursor, "documentId");
          final int _cursorIndexOfPageIndex = CursorUtil.getColumnIndexOrThrow(_cursor, "pageIndex");
          final int _cursorIndexOfOriginalImagePath = CursorUtil.getColumnIndexOrThrow(_cursor, "originalImagePath");
          final int _cursorIndexOfProcessedImagePath = CursorUtil.getColumnIndexOrThrow(_cursor, "processedImagePath");
          final int _cursorIndexOfFilterMode = CursorUtil.getColumnIndexOrThrow(_cursor, "filterMode");
          final int _cursorIndexOfRotationDegrees = CursorUtil.getColumnIndexOrThrow(_cursor, "rotationDegrees");
          final int _cursorIndexOfCropCornersJson = CursorUtil.getColumnIndexOrThrow(_cursor, "cropCornersJson");
          final int _cursorIndexOfOcrText = CursorUtil.getColumnIndexOrThrow(_cursor, "ocrText");
          final List<PageEntity> _result = new ArrayList<PageEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final PageEntity _item;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpDocumentId;
            _tmpDocumentId = _cursor.getString(_cursorIndexOfDocumentId);
            final int _tmpPageIndex;
            _tmpPageIndex = _cursor.getInt(_cursorIndexOfPageIndex);
            final String _tmpOriginalImagePath;
            _tmpOriginalImagePath = _cursor.getString(_cursorIndexOfOriginalImagePath);
            final String _tmpProcessedImagePath;
            _tmpProcessedImagePath = _cursor.getString(_cursorIndexOfProcessedImagePath);
            final String _tmpFilterMode;
            _tmpFilterMode = _cursor.getString(_cursorIndexOfFilterMode);
            final int _tmpRotationDegrees;
            _tmpRotationDegrees = _cursor.getInt(_cursorIndexOfRotationDegrees);
            final String _tmpCropCornersJson;
            if (_cursor.isNull(_cursorIndexOfCropCornersJson)) {
              _tmpCropCornersJson = null;
            } else {
              _tmpCropCornersJson = _cursor.getString(_cursorIndexOfCropCornersJson);
            }
            final String _tmpOcrText;
            if (_cursor.isNull(_cursorIndexOfOcrText)) {
              _tmpOcrText = null;
            } else {
              _tmpOcrText = _cursor.getString(_cursorIndexOfOcrText);
            }
            _item = new PageEntity(_tmpId,_tmpDocumentId,_tmpPageIndex,_tmpOriginalImagePath,_tmpProcessedImagePath,_tmpFilterMode,_tmpRotationDegrees,_tmpCropCornersJson,_tmpOcrText);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getPagesForDocument(final String documentId,
      final Continuation<? super List<PageEntity>> $completion) {
    final String _sql = "SELECT * FROM pages WHERE documentId = ? ORDER BY pageIndex ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, documentId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<PageEntity>>() {
      @Override
      @NonNull
      public List<PageEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfDocumentId = CursorUtil.getColumnIndexOrThrow(_cursor, "documentId");
          final int _cursorIndexOfPageIndex = CursorUtil.getColumnIndexOrThrow(_cursor, "pageIndex");
          final int _cursorIndexOfOriginalImagePath = CursorUtil.getColumnIndexOrThrow(_cursor, "originalImagePath");
          final int _cursorIndexOfProcessedImagePath = CursorUtil.getColumnIndexOrThrow(_cursor, "processedImagePath");
          final int _cursorIndexOfFilterMode = CursorUtil.getColumnIndexOrThrow(_cursor, "filterMode");
          final int _cursorIndexOfRotationDegrees = CursorUtil.getColumnIndexOrThrow(_cursor, "rotationDegrees");
          final int _cursorIndexOfCropCornersJson = CursorUtil.getColumnIndexOrThrow(_cursor, "cropCornersJson");
          final int _cursorIndexOfOcrText = CursorUtil.getColumnIndexOrThrow(_cursor, "ocrText");
          final List<PageEntity> _result = new ArrayList<PageEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final PageEntity _item;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpDocumentId;
            _tmpDocumentId = _cursor.getString(_cursorIndexOfDocumentId);
            final int _tmpPageIndex;
            _tmpPageIndex = _cursor.getInt(_cursorIndexOfPageIndex);
            final String _tmpOriginalImagePath;
            _tmpOriginalImagePath = _cursor.getString(_cursorIndexOfOriginalImagePath);
            final String _tmpProcessedImagePath;
            _tmpProcessedImagePath = _cursor.getString(_cursorIndexOfProcessedImagePath);
            final String _tmpFilterMode;
            _tmpFilterMode = _cursor.getString(_cursorIndexOfFilterMode);
            final int _tmpRotationDegrees;
            _tmpRotationDegrees = _cursor.getInt(_cursorIndexOfRotationDegrees);
            final String _tmpCropCornersJson;
            if (_cursor.isNull(_cursorIndexOfCropCornersJson)) {
              _tmpCropCornersJson = null;
            } else {
              _tmpCropCornersJson = _cursor.getString(_cursorIndexOfCropCornersJson);
            }
            final String _tmpOcrText;
            if (_cursor.isNull(_cursorIndexOfOcrText)) {
              _tmpOcrText = null;
            } else {
              _tmpOcrText = _cursor.getString(_cursorIndexOfOcrText);
            }
            _item = new PageEntity(_tmpId,_tmpDocumentId,_tmpPageIndex,_tmpOriginalImagePath,_tmpProcessedImagePath,_tmpFilterMode,_tmpRotationDegrees,_tmpCropCornersJson,_tmpOcrText);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getPageById(final String id, final Continuation<? super PageEntity> $completion) {
    final String _sql = "SELECT * FROM pages WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, id);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<PageEntity>() {
      @Override
      @Nullable
      public PageEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfDocumentId = CursorUtil.getColumnIndexOrThrow(_cursor, "documentId");
          final int _cursorIndexOfPageIndex = CursorUtil.getColumnIndexOrThrow(_cursor, "pageIndex");
          final int _cursorIndexOfOriginalImagePath = CursorUtil.getColumnIndexOrThrow(_cursor, "originalImagePath");
          final int _cursorIndexOfProcessedImagePath = CursorUtil.getColumnIndexOrThrow(_cursor, "processedImagePath");
          final int _cursorIndexOfFilterMode = CursorUtil.getColumnIndexOrThrow(_cursor, "filterMode");
          final int _cursorIndexOfRotationDegrees = CursorUtil.getColumnIndexOrThrow(_cursor, "rotationDegrees");
          final int _cursorIndexOfCropCornersJson = CursorUtil.getColumnIndexOrThrow(_cursor, "cropCornersJson");
          final int _cursorIndexOfOcrText = CursorUtil.getColumnIndexOrThrow(_cursor, "ocrText");
          final PageEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpDocumentId;
            _tmpDocumentId = _cursor.getString(_cursorIndexOfDocumentId);
            final int _tmpPageIndex;
            _tmpPageIndex = _cursor.getInt(_cursorIndexOfPageIndex);
            final String _tmpOriginalImagePath;
            _tmpOriginalImagePath = _cursor.getString(_cursorIndexOfOriginalImagePath);
            final String _tmpProcessedImagePath;
            _tmpProcessedImagePath = _cursor.getString(_cursorIndexOfProcessedImagePath);
            final String _tmpFilterMode;
            _tmpFilterMode = _cursor.getString(_cursorIndexOfFilterMode);
            final int _tmpRotationDegrees;
            _tmpRotationDegrees = _cursor.getInt(_cursorIndexOfRotationDegrees);
            final String _tmpCropCornersJson;
            if (_cursor.isNull(_cursorIndexOfCropCornersJson)) {
              _tmpCropCornersJson = null;
            } else {
              _tmpCropCornersJson = _cursor.getString(_cursorIndexOfCropCornersJson);
            }
            final String _tmpOcrText;
            if (_cursor.isNull(_cursorIndexOfOcrText)) {
              _tmpOcrText = null;
            } else {
              _tmpOcrText = _cursor.getString(_cursorIndexOfOcrText);
            }
            _result = new PageEntity(_tmpId,_tmpDocumentId,_tmpPageIndex,_tmpOriginalImagePath,_tmpProcessedImagePath,_tmpFilterMode,_tmpRotationDegrees,_tmpCropCornersJson,_tmpOcrText);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
