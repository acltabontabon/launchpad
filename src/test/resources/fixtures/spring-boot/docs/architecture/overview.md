# Architecture overview

The Users service is a single Spring Boot Maven module. Controllers
delegate to services, which talk to JPA repositories backed by Postgres.
