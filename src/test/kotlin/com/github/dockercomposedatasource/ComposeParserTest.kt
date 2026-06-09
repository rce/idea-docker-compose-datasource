package com.github.dockercomposedatasource

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeParserTest {

    @Test
    fun `parses a basic postgres service with map environment`() {
        val yaml = """
            services:
              db:
                image: postgres:16
                environment:
                  POSTGRES_USER: meow
                  POSTGRES_PASSWORD: secret
                  POSTGRES_DB: catdb
                ports:
                  - "5433:5432"
        """.trimIndent()

        val services = ComposeParser.parse(yaml, "docker-compose.yml")
        assertEquals(1, services.size)
        val svc = services.single()
        assertEquals("meow", svc.user)
        assertEquals("catdb", svc.database)
        assertEquals("secret", svc.password)
        assertEquals(5433, svc.port)
        assertEquals("jdbc:postgresql://localhost:5433/catdb", svc.jdbcUrl)
    }

    @Test
    fun `parses list-style environment and defaults`() {
        val yaml = """
            services:
              postgres:
                image: postgres
                environment:
                  - POSTGRES_PASSWORD=hunter2
        """.trimIndent()

        val svc = ComposeParser.parse(yaml, "compose.yml").single()
        // No POSTGRES_USER -> defaults to "postgres"; POSTGRES_DB defaults to user.
        assertEquals("postgres", svc.user)
        assertEquals("postgres", svc.database)
        assertEquals("hunter2", svc.password)
        // No published port -> defaults to 5432.
        assertEquals(5432, svc.port)
    }

    @Test
    fun `honors container_name and ip-prefixed port mapping`() {
        val yaml = """
            services:
              database:
                image: postgis/postgis:16-3.4
                container_name: my_pg
                environment:
                  POSTGRES_PASSWORD: x
                ports:
                  - "127.0.0.1:6543:5432"
        """.trimIndent()

        val svc = ComposeParser.parse(yaml, "docker-compose.yml").single()
        assertEquals("my_pg", svc.serviceName)
        assertEquals(6543, svc.port)
        // Identity follows the compose service key, not the (mutable) container_name.
        assertEquals("database", svc.composeServiceKey)
        assertEquals("docker-compose.yml#database", svc.managedId)
    }

    @Test
    fun `parses long-syntax ports`() {
        val yaml = """
            services:
              db:
                image: postgres:15
                environment:
                  POSTGRES_PASSWORD: x
                ports:
                  - target: 5432
                    published: 5400
                    protocol: tcp
        """.trimIndent()

        assertEquals(5400, ComposeParser.parse(yaml, "compose.yml").single().port)
    }

    @Test
    fun `ignores non-postgres services`() {
        val yaml = """
            services:
              web:
                image: nginx:latest
              cache:
                image: redis:7
        """.trimIndent()

        assertTrue(ComposeParser.parse(yaml, "docker-compose.yml").isEmpty())
    }

    @Test
    fun `returns empty for malformed yaml`() {
        assertTrue(ComposeParser.parse("::: not yaml :::", "x.yml").isEmpty())
    }

    @Test
    fun `password is null when not provided`() {
        val yaml = """
            services:
              db:
                image: postgres:16
        """.trimIndent()

        assertNull(ComposeParser.parse(yaml, "x.yml").single().password)
    }
}
