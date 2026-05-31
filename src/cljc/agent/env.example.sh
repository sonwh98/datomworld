#!/bin/bash

# ==============================================================================
# Agent Tzu - LLM Environment Configuration Example
# ==============================================================================
# Copy this file to env.sh and uncomment the provider you wish to use.
# env.sh is ignored by git to protect your API keys.
#
# Usage:
#   cp env.example.sh env.sh
#   (edit env.sh)
#   source env.sh
#   clj -M -m agent.tzu
# ==============================================================================

# --- OpenAI (Default) ---
# export OPENAI_API_KEY="sk-..."
# export OPENAI_BASE_URL="https://api.openai.com/v1/chat/completions"
# export OPENAI_MODEL="gpt-4o"

# --- DeepSeek ---
# export OPENAI_API_KEY="sk-..."
# export OPENAI_BASE_URL="https://api.deepseek.com/chat/completions"
# export OPENAI_MODEL="deepseek-chat"

# --- Groq ---
# export OPENAI_API_KEY="gsk_..."
# export OPENAI_BASE_URL="https://api.groq.com/openai/v1/chat/completions"
# export OPENAI_MODEL="llama3-70b-8192"

# --- Mistral AI ---
# export OPENAI_API_KEY="your-api-key"
# export OPENAI_BASE_URL="https://api.mistral.ai/v1/chat/completions"
# export OPENAI_MODEL="mistral-large-latest"

# --- Together AI ---
# export OPENAI_API_KEY="your-api-key"
# export OPENAI_BASE_URL="https://api.together.xyz/v1/chat/completions"
# export OPENAI_MODEL="mistralai/Mixtral-8x7B-Instruct-v0.1"

# --- Qwen (Alibaba Cloud DashScope) ---
# export OPENAI_API_KEY="your-api-key"
# export OPENAI_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
# export OPENAI_MODEL="qwen-max"

# --- Kimi (Moonshot AI) ---
# export OPENAI_API_KEY="your-api-key"
# export OPENAI_BASE_URL="https://api.moonshot.cn/v1/chat/completions"
# export OPENAI_MODEL="moonshot-v1-8k"

# --- GLM (Zhipu AI BigModel) ---
# export OPENAI_API_KEY="your-api-key"
# export OPENAI_BASE_URL="https://open.bigmodel.cn/api/paas/v4/chat/completions"
# export OPENAI_MODEL="glm-4-plus"

# --- Local: Ollama ---
# export OPENAI_API_KEY="ollama"
# export OPENAI_BASE_URL="http://localhost:11434/v1/chat/completions"
# export OPENAI_MODEL="llama3"

# --- Local: vLLM ---
# export OPENAI_API_KEY="vllm"
# export OPENAI_BASE_URL="http://localhost:8000/v1/chat/completions"
# export OPENAI_MODEL="your-model-name"

# --- Local: LM Studio ---
# export OPENAI_API_KEY="lm-studio"
# export OPENAI_BASE_URL="http://localhost:1234/v1/chat/completions"
# export OPENAI_MODEL="loaded-model-name"
