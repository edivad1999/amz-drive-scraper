package edivad1999.com


import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.Identity.decode
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.transactions.transactionManager
import java.io.File
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

class AmazonDriveRepository(
    private val client: HttpClient,
    private val authConfig: AuthConfig,
    ) : CoroutineScope {
    private val downloadFolder: File = File(authConfig.downloadFolder)
    val rootFolder = File(downloadFolder, "root").also {
        if (!it.exists()) Files.createDirectory(it.toPath())
    }
    val databaseFile = File(downloadFolder, "root.db").also {
        if (!it.exists()) Files.createFile(it.toPath())
    }
    val database = Database.connect(
        "jdbc:sqlite:$databaseFile",
        "org.sqlite.JDBC",
    )


    fun resolvePath(string: String) = File(downloadFolder, string)

    fun savePath(file: File) = file.invariantSeparatorsPath.substringAfter(downloadFolder.invariantSeparatorsPath)
    val service = CommonResourceService(database)

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO
    fun startDownloadJob() {
        launch(Dispatchers.IO) {
            while (true) {
                runCatching {
                    val downloadedFiles = service.getUnDownloaded(12).mapNotNull {
                        if (it.downloadPath != null) {
                            async(Dispatchers.IO) {
                                val file = resolvePath(it.downloadPath)
                                downloadFile(file, it.id)
                                service.update(it.id, it.copy(downloaded = true))
                            }
                        } else null

                    }
                    downloadedFiles.forEach { it.await() }
                    if (downloadedFiles.isEmpty()) delay(5000)
                }

            }
        }

    }

    fun start() {
        launch {
            startDownloadJob()
        }
        launch {
            while (true) {
                runCatching {
                    println("Restarting parse")
                    val rootFolderResponse = getMainRoot()

                    val rootFolderId = rootFolderResponse.data.first().id

                    val firstRootResponse = getFolder(rootFolderId)
                    if (service.read(rootFolderId) == null) {
                        service.create(rootFolderResponse.data.first())
                    }
                    getRecursiveFolder(rootFolder, firstRootResponse)
                    delay(15.seconds)
                }

            }

        }
    }

    private suspend fun getRecursiveFolder(parentFile: File, response: PagedResponse<CommonResourceResponse>): Unit =
        withContext(Dispatchers.IO) {
            println("API RESP->" + Json.encodeToString(response.data))
            response.data.filter { it.kind == Kind.FOLDER }.forEach {
                val folder = File(parentFile, it.id)
                if (!folder.exists()) Files.createDirectory(folder.toPath())
                val current = service.read(it.id)
                if (current == null) {
                    // doesn't exist create
                    service.create(it.copy(downloadPath = savePath(folder), downloaded = true))
                } else {
                    if (current.version < it.version) {
                        service.update(
                            current.id,
                            it.copy(
                                downloaded = true, downloadPath = savePath(folder)
                            )
                        )
                    }
                }
                getRecursiveFolder(folder, getFolder(it.id))
            }
            response.data.filter { it.kind == Kind.FILE }.forEach {

                val folder = File(parentFile, it.id)
                if (folder.exists().not()) Files.createDirectory(folder.toPath())
                val file = File(folder, it.name)

                val current = service.read(it.id)
                if (current == null) {
                    // doesn't exist create
                    service.create(it.copy(downloadPath = savePath(file)))
                } else {
                    if (current.version < it.version) {
                        folder.listFiles()?.forEach { it.delete() }
                        service.update(
                            current.id,
                            it.copy(downloaded = false, downloadPath = savePath(file))
                        )
                    }
                }
            }

        }

    suspend fun downloadFile(file: File, fileId: String) = withContext(Dispatchers.IO) {
        file.delete()
        file.createNewFile()
        client.prepareGet("$nodesEndpoint/$fileId/contentRedirection") {
            parameter("querySuffix", "?download=true")
            setAmzRetrievedHeaders()
        }.execute { httpResponse ->
            println("NowDownloading ${file.path}")
            val channel = httpResponse.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val packet = channel.readRemaining(1024L * 50)
                while (!packet.isEmpty) {
                    val bytes = packet.readBytes()
                    file.appendBytes(bytes)
                }
            }
            println("A file saved to ${file.path}")
        }
    }

    suspend fun getMainRoot() = client.get(
        nodesEndpoint,
    ) {
        parameter("filters", "isRoot:true")
        parameter("resourceVersion", "V2")
        parameter("ContentType", "JSON")

        setAmzRetrievedHeaders()
    }.body<PagedResponse<CommonResourceResponse>>()

    suspend fun getFolder(folderId: String) = client.get(
        nodesEndpoint,
    ) {
        parameter("filters", "kind:(FILE* OR FOLDER*) AND status:(AVAILABLE*) AND parents:$folderId")
        parameter("resourceVersion", "V2")
        parameter("ContentType", "JSON")
        parameter("asset", "ALL")
        parameter("tempLink", false)
//        parameter("offset", 0)

        setAmzRetrievedHeaders()
    }.body<PagedResponse<CommonResourceResponse>>()

    fun HttpRequestBuilder.setAmzRetrievedHeaders() {
        headers {
            accept(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, authConfig.userAgent)
            header("x-amzn-sessionid", authConfig.sessionId)
            header(HttpHeaders.Cookie, authConfig.cookie)
        }
    }

    val nodesEndpoint = "https://www.amazon.com/drive/v1/nodes"

}

@Serializable
enum class Kind {
    FOLDER, FILE, ASSET
}

@Serializable
data class CommonResourceResponse(
    val name: String = "",
    val id: String,
    val isRoot: Boolean,
    val modifiedDate: String,
    val createdDate: String,
    val kind: Kind,
    val parents: List<String>,
    val downloadPath: String? = null,
    val version: Int,
    val downloaded: Boolean = false
) {

}

@Serializable
data class PagedResponse<T>(
    val count: Int,
    val data: List<T>,
)

@Serializable
data class AuthConfig(val cookie: String, val userAgent: String, val sessionId: String, val downloadFolder: String)