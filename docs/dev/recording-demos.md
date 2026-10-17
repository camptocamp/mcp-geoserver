# Recording the README demos

Prompts: README, "Example sessions".

Setup
- Terminal about 100x28, font 16 pt, dark theme, clean prompt. Run `make start`, enable MCP, then `mkdir -p docs/img`.
- Register the server once: `claude mcp add --transport http geoserver http://localhost:8080/geoserver/mcp --header "Authorization: Basic YWRtaW46Z2Vvc2VydmVy"`.
- Start interactive `claude` (Haiku keeps it fast: `claude --model haiku`). Allow the geoserver tools when asked, ahead of recording.
- Before demo 2, make sure workspace `demo` does not exist (delete it with `curl -u admin:geoserver -X DELETE "http://localhost:8080/geoserver/rest/workspaces/demo?recurse=true"`). Demo 3 needs the result of demo 2.

Per GIF
1. Open Peek, frame the terminal window, set GIF output, frame rate 10 to 15.
2. Click Record, wait one second, paste the prompt, press Enter.
3. Stop when the answer is complete. Keep each clip under about 30 s (trim or speed up if longer).
4. Save as `docs/img/demo-1.gif`, `demo-2.gif`, `demo-3.gif`, `demo-4.gif` (prompts above, in order).

Embed in the README

```markdown
![What is on this GeoServer](docs/img/demo-1.gif)
![Publish, style and check a layer](docs/img/demo-2.gif)
![Query features and summarize](docs/img/demo-3.gif)
![Show the REST calls](docs/img/demo-4.gif)
```
