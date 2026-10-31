#!/bin/bash

# Test Mistral API key with codestral-latest model
curl -X POST "https://api.mistral.ai/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer mstrl_EvccdKBMfaVxlxBzH8c69EJKvJElmnm5_1YxGVh" \
  -d '{
    "model": "codestral-latest",
    "messages": [{"role": "user", "content": "Say hello"}],
    "max_tokens": 10
  }'
