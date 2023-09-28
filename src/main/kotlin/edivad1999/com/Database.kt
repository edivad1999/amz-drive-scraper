package edivad1999.com

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction


class CommonResourceService(private val database: Database, private val json: Json = Json) {
    object CommonResourceTable : Table() {
        val name = varchar("name", 1000)
        val id = varchar("id", 100)
        val isRoot = bool("isRoot")
        val downloaded = bool("downloaded")
        val modifiedDate = varchar("modifiedDate", 100)
        val createdDate = varchar("createdDate", 100)
        val kind = enumeration("kind", Kind::class)
        val parents = varchar("parent", 10000)
        val version = integer("version")
        val downloadPath = varchar("downloadPath", 1000).nullable()


        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(CommonResourceTable)
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    suspend fun create(response: CommonResourceResponse): String = dbQuery {
        CommonResourceTable.insert {
            it[id] = response.id
            it[name] = response.name
            it[isRoot] = response.isRoot
            it[modifiedDate] = response.modifiedDate
            it[createdDate] = response.createdDate
            it[kind] = response.kind
            it[parents] = json.encodeToString(response.parents)
            it[downloadPath] = response.downloadPath
            it[downloaded] = response.downloaded
            it[version] = response.version
        }[CommonResourceTable.id]
    }


    suspend fun read(id: String): CommonResourceResponse? {
        return dbQuery {
            CommonResourceTable.select { CommonResourceTable.id eq id }
                .map {
                    CommonResourceResponse(
                        name = it[CommonResourceTable.name],
                        id = it[CommonResourceTable.id],
                        isRoot = it[CommonResourceTable.isRoot],
                        modifiedDate = it[CommonResourceTable.modifiedDate],
                        createdDate = it[CommonResourceTable.createdDate],
                        kind = it[CommonResourceTable.kind],
                        parents = json.decodeFromString(it[CommonResourceTable.parents]),
                        downloadPath = it[CommonResourceTable.downloadPath],
                        version = it[CommonResourceTable.version],
                        downloaded = it[CommonResourceTable.downloaded]
                    )
                }
                .singleOrNull()
        }
    }

    suspend fun update(id: String, response: CommonResourceResponse) {
        dbQuery {
            CommonResourceTable.update({ CommonResourceTable.id eq id }) {
                it[name] = response.name
                it[isRoot] = response.isRoot
                it[modifiedDate] = response.modifiedDate
                it[createdDate] = response.createdDate
                it[kind] = response.kind
                it[parents] = json.encodeToString(response.parents)
                it[downloadPath] = response.downloadPath
                it[version] = response.version
                it[downloaded] = response.downloaded

            }
        }
    }

    suspend fun delete(id: String) {
        dbQuery {
            CommonResourceTable.deleteWhere { CommonResourceTable.id.eq(id) }
        }
    }

    suspend fun getUnDownloaded(limit: Int) = dbQuery {
        CommonResourceTable.select {
            (CommonResourceTable.downloaded eq false) and (CommonResourceTable.isRoot eq false)

        }.limit(limit).map {
            CommonResourceResponse(
                name = it[CommonResourceTable.name],
                id = it[CommonResourceTable.id],
                isRoot = it[CommonResourceTable.isRoot],
                modifiedDate = it[CommonResourceTable.modifiedDate],
                createdDate = it[CommonResourceTable.createdDate],
                kind = it[CommonResourceTable.kind],
                parents = json.decodeFromString(it[CommonResourceTable.parents]),
                downloadPath = it[CommonResourceTable.downloadPath],
                version = it[CommonResourceTable.version],
                downloaded = it[CommonResourceTable.downloaded]
            )
        }.toList()

    }
}
