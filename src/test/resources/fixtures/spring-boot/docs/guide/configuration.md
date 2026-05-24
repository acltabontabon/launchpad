# Configuration

Sample Service reads its configuration from `application.properties`. The
following keys are recognised:

- `server.port`: HTTP port to listen on.
- `service.upstream.url`: HTTPS URL of the upstream service.
- `service.upstream.timeout`: request timeout in seconds.
