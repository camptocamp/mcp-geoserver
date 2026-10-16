# GeoServer MCP: common tasks. Run `make` or `make help` to list targets.
#
# Examples:
#   make start           # start (or create) the GeoServer container, then wait
#   make test            # Maven unit tests + the skill's fixer self-check
#   make build           # package the extension jars
#   make logs            # tail GeoServer logs
#   make remove          # stop and delete the GeoServer container
#   make hub-fetch LIMIT=20   # pull 20 styles from the QGIS hub (then convert in QGIS, then hub-score)

GS       ?= vanilla-geoserver-1
GS_IMAGE ?= docker.osgeo.org/geoserver:3.0.1
GS_DATA  ?= vanilla_geoserver_data
GS_URL   ?= http://localhost:8080/geoserver
GS_AUTH  ?= admin:geoserver
SKILL    := skills/qgis-to-geoserver
LIMIT    ?= 12

.DEFAULT_GOAL := help
.PHONY: help start run stop restart recreate remove logs wait status shell web fonts \
        build test it clean install-skill fixer-test fix check \
        hub-fetch hub-convert hub-score hub-show

help: ## List targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-12s\033[0m %s\n",$$1,$$2}'

## --- GeoServer container ---
start: ## Start (or create) GeoServer, then wait until ready
	@docker start $(GS) 2>/dev/null || $(MAKE) run
	@$(MAKE) wait

run: ## Create and run the GeoServer container
	docker run -d --name $(GS) -p 8080:8080 \
		-e SKIP_DEMO_DATA=false -e PROXY_BASE_URL=$(GS_URL) \
		-v $(GS_DATA):/opt/geoserver_data \
		-v "$(PWD)/docker/vanilla/additional_libs:/opt/additional_libs" \
		$(GS_IMAGE)

stop: ## Stop the GeoServer container
	docker stop $(GS)

restart: stop start ## Restart GeoServer

recreate: remove run wait ## Delete and recreate the container (keeps the data volume)

remove: ## Stop and delete the GeoServer container (keeps the data volume)
	docker rm -f $(GS)

logs: ## Tail GeoServer logs
	docker logs -f --tail 100 $(GS)

status: ## Show container state and GeoServer version
	@docker ps -a --filter name=$(GS) --format 'table {{.Names}}\t{{.Status}}'
	@curl -s -u $(GS_AUTH) $(GS_URL)/rest/about/version.json -o /dev/null -w 'rest: HTTP %{http_code}\n' || true

shell: ## Open a shell in the GeoServer container
	docker exec -it $(GS) bash

web: ## Print the GeoServer web URL and credentials
	@echo "$(GS_URL)/web  ($(GS_AUTH))"

wait: ## Wait until GeoServer answers (polls from inside the container)
	@docker exec $(GS) sh -c 'for i in $$(seq 1 40); do \
		curl -sf -u $(GS_AUTH) $(GS_URL)/rest/about/version.json >/dev/null && { echo "geoserver ready"; exit 0; }; \
		sleep 3; done; echo "geoserver not ready"; exit 1'

fonts: ## Print the fonts GeoServer has (for the fixer's --available)
	@curl -s -u $(GS_AUTH) $(GS_URL)/rest/fonts.json

## --- build / test ---
build: ## Package the extension jars (skips tests)
	./mvnw -q -DskipTests package

test: ## Maven unit tests + the skill fixer self-check
	./mvnw -q test
	python3 -I $(SKILL)/test_fix_sld_functions.py

it: ## Maven integration tests (mcp-it)
	./mvnw -q verify

clean: ## Maven clean + remove hub scratch under /tmp
	./mvnw -q clean
	rm -rf /tmp/hub_corpus /tmp/hub_sld /tmp/hub_img /tmp/hub_report.md

## --- QGIS to GeoServer styling skill ---
install-skill: ## Symlink the qgis-to-geoserver skill into ~/.claude/skills
	ln -sfn "$(PWD)/$(SKILL)" ~/.claude/skills/qgis-to-geoserver
	@echo "linked ~/.claude/skills/qgis-to-geoserver"

fixer-test: ## Run only the fixer self-check (fast, no Maven)
	python3 -I $(SKILL)/test_fix_sld_functions.py

fix: ## Fix SLDs for GeoServer in SLD=<file|dir> (functions, fonts, labels, inline SVGs)
	@test -n "$(SLD)" || { echo "usage: make fix SLD=/path/to/sld"; exit 1; }
	python3 -I $(SKILL)/fix-sld-functions.py --font-fallback SansSerif \
		--available "$$(curl -s -u $(GS_AUTH) $(GS_URL)/rest/fonts.json | python3 -c 'import sys,json;print(",".join(json.load(sys.stdin)["fonts"]))')" \
		$(SLD)

check: ## Report what the fixer would change in SLD=<file|dir>, write nothing
	@test -n "$(SLD)" || { echo "usage: make check SLD=/path/to/sld"; exit 1; }
	python3 -I $(SKILL)/fix-sld-functions.py --check $(SLD)

## --- QGIS hub corpus test (see docs/dev/qgis-hub-corpus.md) ---
hub-fetch: ## Download LIMIT styles from the QGIS hub into /tmp/hub_corpus
	python3 -I $(SKILL)/tools/fetch_hub_styles.py --out /tmp/hub_corpus --limit $(LIMIT)

hub-convert: ## Print the QGIS-console step (conversion must run inside QGIS)
	@echo 'Conversion runs inside QGIS (GeoCat Bridge installed). In the QGIS Python console:'
	@echo '  import sys; sys.path.insert(0, "$(PWD)/$(SKILL)/tools")'
	@echo '  from hub_corpus_test import convert'
	@echo '  convert("/tmp/hub_corpus", "/tmp/hub_sld",'
	@echo '          "$(PWD)/$(SKILL)/testdata/fixtures.gpkg",'
	@echo '          "$(PWD)/$(SKILL)/fix-sld-functions.py",'
	@echo '          available_fonts="DejaVu Sans,SansSerif,Serif")   # or paste `make fonts`'

hub-score: ## Render + score the converted hub styles, write report + montages
	python3 -I $(SKILL)/tools/hub_corpus_score.py --sld-dir /tmp/hub_sld \
		--gpkg $(SKILL)/testdata/fixtures.gpkg \
		--report /tmp/hub_report.md --img-dir /tmp/hub_img
	@echo "report: /tmp/hub_report.md   montages: /tmp/hub_img"

hub-show: ## Print the last scorecard and open the montages
	@cat /tmp/hub_report.md 2>/dev/null || { echo "no report yet: run make hub-score"; exit 1; }
	@[ -d /tmp/hub_img ] && { xdg-open /tmp/hub_img >/dev/null 2>&1 || echo "montages in /tmp/hub_img"; } || true
