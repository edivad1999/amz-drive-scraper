package edivad1999.com


import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.Database
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AmazonDriveRepository(
    private val client: HttpClient,
    private val authConfig: AuthConfig, override val coroutineContext: CoroutineContext,
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

    fun startDownloadJob() {
        launch(Dispatchers.IO) {
            while (true) {
                delay(200)
                runCatching {
                    val downloadedFiles = service.getUnDownloaded(8).mapNotNull { response ->
                        if (response.downloadPath != null) {
                            async(Dispatchers.IO) {
                                runCatching {
                                    val file = resolvePath(response.downloadPath)
                                    file.delete()
                                    file.createNewFile()
                                    downloadFile(file, response.id)
                                    service.update(response.id, response.copy(downloaded = true))
                                }.onFailure {
                                    it.printStackTrace()
                                    service.update(response.id, response.copy(downloaded = false))
                                }

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

                    if (service.read(rootFolderId) == null) {
                        service.create(rootFolderResponse.data.first())
                    }

                    var offset = 0
                    do {
                        val all = runCatching { getAll(offset) }.onSuccess {
                            getRecursiveFolder(rootFolder, it)
                            offset += 200

                        }.onFailure { it.printStackTrace() }.getOrNull()
                    } while (offset <= (all?.count ?: Int.MAX_VALUE))

//                    getRecursiveFolder(rootFolder, firstRootResponse)

                }.onFailure { it.printStackTrace() }
                delay(60.seconds)

            }

        }
    }

    private suspend fun getRecursiveFolder(root: File, response: PagedResponse<CommonResourceResponse>): Unit {
        delay(500.milliseconds)
        println("API RESP->" + Json.encodeToString(response.data.map { it.name }))
        response.data.filter { it.kind == Kind.FOLDER }.forEach {

            val current = service.read(it.id)
            if (current == null) {
                // doesn't exist create
                service.create(it.copy(downloadPath = null, downloaded = true))
            } else {
                if (current.version < it.version) {
                    service.update(
                        current.id,
                        it.copy(
                            downloaded = true, downloadPath = null
                        )
                    )
                }
            }
//                getRecursiveFolder(folder, getFolder(it.id))
        }
        response.data.filter { it.kind == Kind.FILE }.forEach {

            val folder = File(root, it.id)
            if (folder.exists().not()) Files.createDirectory(folder.toPath())
            val file = File(folder, it.name)
            val current = service.read(it.id)
            if (current == null) {
                // doesn't exist create
                service.create(it.copy(downloadPath = savePath(file)))
            } else {
                val file = resolvePath(current.downloadPath!!)
                if (current.version < it.version) {
                    folder.listFiles()?.forEach { it.delete() }
                    service.update(
                        current.id,
                        it.copy(downloaded = false, downloadPath = savePath(file))
                    )

                }

                if (
                    file.exists() &&
                    current.downloaded &&
                    current.size > 0 &&
                    file.length() > 0 &&
                    (file.length() <= (current.size - 1024) || file.length() >= (current.size + 1024))
                ) {

                    folder.listFiles()?.forEach { it.delete() }
                    println("$file downloaded partially")
                    service.update(
                        current.id, it.copy(downloaded = false, downloadPath = savePath(file))
                    )
                }
            }
        }

    }

    suspend fun downloadFile(file: File, fileId: String) {
        client.prepareGet("$nodesEndpoint/$fileId/contentRedirection") {
            parameter("querySuffix", "?download=true")
            setAmzRetrievedHeaders()
        }.execute { httpResponse ->
            println("NowDownloading ${file.path}")
            val channel = httpResponse.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val packet = channel.readRemaining(1024L * 10)
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

    suspend fun getAll(offset: Int) = client.get(
        nodesEndpoint,
    ) {
        parameter("filters", "kind:(FILE* OR FOLDER*) AND status:(AVAILABLE*)")
        parameter("sort", "['kind DESC']")
        parameter("resourceVersion", "V2")
        parameter("ContentType", "JSON")
        parameter("asset", "ALL")
        parameter("tempLink", false)
        parameter("offset", offset)
        parameter("limit", 200)

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
    FOLDER, FILE,
}

@Serializable
data class CommonResourceResponse(
    val name: String = "",
    val id: String,
    @SerialName("contentProperties") val _contentProperties: ContentProperties = ContentProperties(),
    val isRoot: Boolean,
    val modifiedDate: String,
    val createdDate: String,
    val kind: Kind,
    val parents: List<String>,
    val downloadPath: String? = null,
    val version: Int,
    val downloaded: Boolean = false,
) {
    val size get() = _contentProperties.size
    val contentProperties get() = _contentProperties.contentType
}

@Serializable
data class ContentProperties(val size: Long = 0, val contentType: String = "")


@Serializable
data class PagedResponse<T>(
    val count: Int,
    val data: List<T>,
)

@Serializable
data class AuthConfig(val cookie: String, val userAgent: String, val sessionId: String, val downloadFolder: String)