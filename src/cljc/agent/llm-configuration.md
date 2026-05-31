# LLM Configuration for Agent Tzu

Agent Tzu (`agent.tzu`) is designed to work with any OpenAI-compatible Chat Completions API. This allows you to use a wide range of Large Language Models (LLMs), including both cloud-hosted services and local instances.

## Environment Variables

Configuration is handled through the following environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `OPENAI_API_KEY` | Your API key for the chosen LLM provider. | (Required) |
| `OPENAI_BASE_URL` | The base URL of the OpenAI-compatible API. | `https://api.openai.com/v1/chat/completions` |
| `OPENAI_MODEL` | The model identifier to use (e.g., `gpt-4o`, `deepseek-chat`). | `gpt-4o` |

## Supported LLM Providers

### OpenAI
The default configuration is set up for OpenAI.
- **Base URL:** `https://api.openai.com/v1/chat/completions`
- **Model:** `gpt-4o`, `gpt-4`, `gpt-3.5-turbo`
- **API Key:** Obtain from [platform.openai.com](https://platform.openai.com/)

### DeepSeek
DeepSeek offers a high-performance, cost-effective OpenAI-compatible API.
- **Base URL:** `https://api.deepseek.com/chat/completions`
- **Model:** `deepseek-chat`
- **API Key:** Obtain from [platform.deepseek.com](https://platform.deepseek.com/)

### Groq
Groq provides exceptionally fast inference for various open-source models.
- **Base URL:** `https://api.groq.com/openai/v1/chat/completions`
- **Model:** `llama3-70b-8192`, `mixtral-8x7b-32768`
- **API Key:** Obtain from [console.groq.com](https://console.groq.com/)

### Mistral AI
- **Base URL:** `https://api.mistral.ai/v1/chat/completions`
- **Model:** `mistral-large-latest`, `mistral-small-latest`
- **API Key:** Obtain from [console.mistral.ai](https://console.mistral.ai/)

### Together AI
Together AI hosts a vast array of open-source models.
- **Base URL:** `https://api.together.xyz/v1/chat/completions`
- **Model:** `mistralai/Mixtral-8x7B-Instruct-v0.1`, etc.
- **API Key:** Obtain from [api.together.xyz](https://api.together.xyz/)

### Qwen (Alibaba Cloud)
DashScope provides an OpenAI-compatible interface for the Qwen series.
- **Base URL:** `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`
- **Model:** `qwen-max`, `qwen-plus`, `qwen-turbo`
- **API Key:** Obtain from [dashscope.console.aliyun.com](https://dashscope.console.aliyun.com/)

### Kimi (Moonshot AI)
Moonshot AI's platform is natively OpenAI-compatible.
- **Base URL:** `https://api.moonshot.cn/v1/chat/completions`
- **Model:** `moonshot-v1-8k`, `moonshot-v1-32k`, `moonshot-v1-128k`
- **API Key:** Obtain from [platform.moonshot.cn](https://platform.moonshot.cn/)

### GLM (Zhipu AI)
Zhipu AI offers an OpenAI-compatible endpoint for the GLM series.
- **Base URL:** `https://open.bigmodel.cn/api/paas/v4/chat/completions`
- **Model:** `glm-4-plus`, `glm-4-9b`
- **API Key:** Obtain from [open.bigmodel.cn](https://open.bigmodel.cn/)

## Local LLMs

You can run models locally using tools that expose an OpenAI-compatible API.

### Ollama
[Ollama](https://ollama.com/) is an easy-to-use tool for running LLMs locally.
- **Base URL:** `http://localhost:11434/v1/chat/completions`
- **Model:** `llama3`, `mistral`, `phi3`, etc. (depends on what you have pulled)
- **API Key:** Typically not required for local use, but `OPENAI_API_KEY` must still be set (use any dummy string like `ollama`).

### vLLM
[vLLM](https://github.com/vllm-project/vllm) is a high-throughput serving engine.
- **Base URL:** `http://<your-server-ip>:8000/v1/chat/completions`
- **Model:** Specified when starting the vLLM server.
- **API Key:** Depends on your server configuration.

### LM Studio
[LM Studio](https://lmstudio.ai/) provides a GUI for running local LLMs with an OpenAI-compatible server.
- **Base URL:** `http://localhost:1234/v1/chat/completions`
- **Model:** The model currently loaded in the app.
- **API Key:** Typically not required.

## Example Setup (`env.sh`)

You can create a local `env.sh` file (ignored by git) to manage your configuration by copying the provided example:

```bash
cp src/cljc/agent/env.example.sh env.sh
# Edit env.sh to add your API key and choose your provider
```

The example file contains templates for all supported providers.

Then source it before running:
```bash
source env.sh
clj -M -m agent.tzu
```
