# Room resolves the generated database by name at runtime.
-keep class com.moyu.reader.data.ReaderDatabase_Impl { *; }

# PDFBox only touches the optional JPEG 2000 decoder when a page contains JPX
# images. Text extraction never does, so the missing library is harmless.
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.filter.JPXFilter
