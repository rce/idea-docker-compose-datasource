package fi.rce.idea.datasources

import org.yaml.snakeyaml.Yaml

/**
 * Parses docker-compose YAML content and extracts PostgreSQL services.
 *
 * Detection is image-based: any service whose `image` references postgres or
 * postgis (e.g. `postgres:16`, `postgis/postgis`, `bitnami/postgresql`) is
 * treated as a Postgres database.
 */
object ComposeParser {

    private const val DEFAULT_HOST = "localhost"
    private const val POSTGRES_CONTAINER_PORT = 5432
    private const val DEFAULT_USER = "postgres"

    private val POSTGRES_IMAGE_HINTS = listOf("postgres", "postgis")

    fun parse(content: String, sourceFile: String): List<PostgresService> {
        val root = runCatching { Yaml().load<Any?>(content) }.getOrNull() as? Map<*, *>
            ?: return emptyList()
        val services = root["services"] as? Map<*, *> ?: return emptyList()

        return services.entries.mapNotNull { (key, value) ->
            val serviceName = key?.toString() ?: return@mapNotNull null
            val service = value as? Map<*, *> ?: return@mapNotNull null
            if (!isPostgres(service)) return@mapNotNull null
            toPostgresService(serviceName, service, sourceFile)
        }
    }

    private fun isPostgres(service: Map<*, *>): Boolean {
        val image = service["image"]?.toString()?.lowercase() ?: return false
        return POSTGRES_IMAGE_HINTS.any { image.contains(it) }
    }

    private fun toPostgresService(
        serviceName: String,
        service: Map<*, *>,
        sourceFile: String,
    ): PostgresService {
        val env = readEnvironment(service)
        val user = env["POSTGRES_USER"] ?: DEFAULT_USER
        // Postgres defaults POSTGRES_DB to the value of POSTGRES_USER.
        val database = env["POSTGRES_DB"] ?: user
        val password = env["POSTGRES_PASSWORD"]
        val hostPort = readPublishedPort(service) ?: POSTGRES_CONTAINER_PORT
        val name = service["container_name"]?.toString() ?: serviceName

        return PostgresService(
            composeServiceKey = serviceName,
            serviceName = name,
            host = DEFAULT_HOST,
            port = hostPort,
            database = database,
            user = user,
            password = password,
            sourceFile = sourceFile,
        )
    }

    /** Supports both list (`- KEY=VALUE`) and map (`KEY: VALUE`) environment forms. */
    private fun readEnvironment(service: Map<*, *>): Map<String, String> {
        return when (val env = service["environment"]) {
            is Map<*, *> -> env.entries.mapNotNull { (k, v) ->
                val key = k?.toString() ?: return@mapNotNull null
                key to (v?.toString() ?: "")
            }.toMap()

            is List<*> -> env.mapNotNull { entry ->
                val text = entry?.toString() ?: return@mapNotNull null
                val idx = text.indexOf('=')
                if (idx < 0) text to "" else text.substring(0, idx) to text.substring(idx + 1)
            }.toMap()

            else -> emptyMap()
        }
    }

    /**
     * Finds the host port published for the Postgres container port (5432).
     * Handles short syntax (`"5433:5432"`, `"127.0.0.1:5433:5432"`, `"5432"`)
     * and long syntax (`{target: 5432, published: 5433}`).
     */
    private fun readPublishedPort(service: Map<*, *>): Int? {
        val ports = service["ports"] as? List<*> ?: return null
        for (entry in ports) {
            when (entry) {
                is Map<*, *> -> {
                    val target = entry["target"]?.toString()?.toIntOrNull()
                    if (target == POSTGRES_CONTAINER_PORT) {
                        val published = entry["published"]?.toString()?.toIntOrNull()
                        if (published != null) return published
                    }
                }

                else -> {
                    val parsed = parseShortPort(entry?.toString() ?: continue)
                    if (parsed != null) return parsed
                }
            }
        }
        return null
    }

    private fun parseShortPort(spec: String): Int? {
        val parts = spec.trim().split(":")
        return when (parts.size) {
            // "5432" -> only the container port is given; host port is ephemeral.
            1 -> null
            // "host:container"
            2 -> if (parts[1].toIntOrNull() == POSTGRES_CONTAINER_PORT) parts[0].toIntOrNull() else null
            // "ip:host:container"
            3 -> if (parts[2].toIntOrNull() == POSTGRES_CONTAINER_PORT) parts[1].toIntOrNull() else null
            else -> null
        }
    }
}
