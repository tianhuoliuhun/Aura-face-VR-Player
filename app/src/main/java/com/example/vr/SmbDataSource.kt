package com.example.vr

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream

/**
 * ExoPlayer DataSource that streams video over SMB (SMB2/SMB3) via jcifs-ng,
 * so LAN shares can be played directly without downloading first.
 */
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }

    private var inputStream: SmbFileInputStream? = null
    private var openedUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        openedUri = dataSpec.uri
        try {
            val smbFile = SmbFile(dataSpec.uri.toString())
            inputStream = SmbFileInputStream(smbFile)
            if (dataSpec.position != 0L) {
                inputStream?.skip(dataSpec.position)
            }
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        } catch (e: Exception) {
            throw java.io.IOException("SMB open failed: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val stream = inputStream ?: return C.RESULT_END_OF_INPUT
        val read = stream.read(buffer, offset, length)
        if (read >= 0) {
            bytesTransferred(read)
        }
        return if (read == -1) C.RESULT_END_OF_INPUT else read
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        try {
            inputStream?.close()
        } catch (_: Exception) {
        }
        inputStream = null
        openedUri = null
    }
}
