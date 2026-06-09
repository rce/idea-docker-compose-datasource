package fi.rce.idea.datasources

/**
 * A PostgreSQL service discovered in a docker-compose file, normalized into the
 * connection details we need to register an IDE data source.
 */
data class PostgresService(
    /** The stable key of the service in the compose `services:` map (identity). */
    val composeServiceKey: String,
    /** Display name: `container_name` if set, otherwise the compose service key. */
    val serviceName: String,
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val password: String?,
    /** Path of the compose file this came from. */
    val sourceFile: String,
) {
    val jdbcUrl: String
        get() = "jdbc:postgresql://$host:$port/$database"

    /** Human-readable data source name shown in the Database tool window. */
    val dataSourceName: String
        get() = "$serviceName ($database@$host:$port)"

    /**
     * Stable identity of this service across edits, used to match an existing
     * plugin-managed data source so modifications update rather than duplicate it.
     * Tied to (file, service key) — not to the URL, which changes when edited.
     */
    val managedId: String
        get() = "$sourceFile#$composeServiceKey"
}
