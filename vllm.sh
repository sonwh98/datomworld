#!/bin/bash
source ~/vllm-env/bin/activate && vllm serve Qwen/Qwen3.5-4B --enable-auto-tool-choice --tool-call-parser hermes --max-model-len 4096 --enforce-eager --gpu-memory-utilization 0.95
