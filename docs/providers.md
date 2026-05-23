# Local-AI providers

Launchpad talks to a local-inference runtime through Spring AI. Two backends
are wired into the same `LlmProviderRouter`:

- **Ollama** - native API at `/api/chat` and `/api/generate`.
- **OpenAI-compatible** - any server that speaks `/v1/chat/completions`. This
  covers LM Studio, llama.cpp's `server`, vLLM, and most hosted gateways.

Pick the one you already run. Switch later in `/settings` - no restart, no
rebuild.

## Choosing a provider

| Provider          | When to pick it |
|-------------------|------------------|
| `ollama`          | You already use Ollama. Easiest model management (`ollama pull <model>`). |
| `openai-compatible` | You run LM Studio (GUI model browser), llama.cpp (custom builds), vLLM (GPU serving), or a hosted gateway. |
| `auto`            | You're trying both, or your environment varies. Launchpad probes `/api/tags` first, then `/v1/models`. |

## Per-provider setup

### Ollama

1. Install from <https://ollama.com>.
2. Start the daemon: `ollama serve`.
3. Pull a model: `ollama pull qwen2.5-coder:7b`.
4. In Launchpad `/settings`: provider `ollama`, base URL `http://localhost:11434`, model `qwen2.5-coder:7b`. API key field stays blank.

### LM Studio

1. Install from <https://lmstudio.ai>.
2. Download a model (Hermes, Qwen Coder, Llama 3.x, ...) via the in-app browser.
3. Open the **Local Server** tab and start the server. Note the base URL (default `http://localhost:1234/v1`) and the loaded model id (visible at the top of the server tab).
4. In Launchpad `/settings`: provider `openai-compatible`, base URL `http://localhost:1234/v1`, model the exact id LM Studio shows. API key blank.

### llama.cpp server

1. Build llama.cpp (see <https://github.com/ggerganov/llama.cpp>).
2. Start the OpenAI-compatible server:
   ```bash
   ./server -m models/your-model.gguf --host 0.0.0.0 --port 8080
   ```
3. In Launchpad `/settings`: provider `openai-compatible`, base URL `http://localhost:8080/v1`, model the filename you loaded (whatever `/v1/models` returns; default is often just the file basename). API key blank.

### vLLM

1. Install vLLM (`pip install vllm` or use the published Docker image).
2. Start the OpenAI-compatible server:
   ```bash
   vllm serve <huggingface-model-id> --host 0.0.0.0 --port 8000
   ```
3. In Launchpad `/settings`: provider `openai-compatible`, base URL `http://localhost:8000/v1`, model the Hugging Face id you served. API key required only if you started vLLM with `--api-key <token>`.

### Hosted OpenAI-compatible gateways (Groq, Together, Fireworks, ...)

Any service that exposes `/v1/chat/completions` works. Use the gateway's base URL, the gateway-specific model id, and paste your API key into the API key field. Mind that you're now sending traffic over the network - Launchpad does not warn about this because the user picked the URL.

## API keys

The API key is optional. Blank means no `Authorization` header is sent, which matches LM Studio and llama.cpp out of the box.

For setups where you'd rather not keep the key in the plaintext config file, set `LAUNCHPAD_LLM_API_KEY` in your shell - the env var overrides any value in `~/.launchpad/config.properties`.

## Troubleshooting

- **"daemon unreachable"** - the base URL doesn't accept HTTP requests. Confirm the server is running and the port is open. Health probes use `GET /api/tags` (Ollama) or `GET /v1/models` (OpenAI-compatible) - hit those manually with `curl` to isolate.
- **"model missing"** - the server is up but the configured model id isn't in its model list. For Ollama, `ollama list` shows what's pulled; for OpenAI-compatible, `curl http://<base>/v1/models` shows what's loaded.
- **Auto picks the wrong one** - pin the provider explicitly in `/settings`. Auto-detect is a convenience, not a constraint.
