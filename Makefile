.PHONY: start stop build test deploy preflight check-context backup backup-replay-blog backup-replay-personalist backup-replay-tracker clean

start:
	@DEV=true clj -X:run

stop:
	@pkill -f 'plurama.server' || true

build:
	clj -T:build uber

test:
	$(MAKE) -C ../personalist test
	$(MAKE) -C ../blog test
	$(MAKE) -C ../tracker test
	$(MAKE) -C ../treina test
	$(MAKE) -C ../music test
	$(MAKE) -C ../tracker e2e-docker

backup:
	@mkdir -p ../backups
	@OUT="../backups/plurama-data.$$(date +%Y-%m-%d.%H-%M).tar.gz" && \
		echo "Backing up /app/data from fly machine to $$OUT ..." && \
		fly ssh console --app plurama -C "tar -czf - -C / app/data" > "$$OUT" && \
		echo "Wrote $$OUT ($$(du -h "$$OUT" | cut -f1))"

# The build context is the workspace root (see the `fly deploy` line below), so
# it is ../.dockerignore that decides what gets uploaded — not .gitignore, and
# not the .dockerignore files inside the app dirs. That file is an allowlist, so
# a new app must be added to it as well as to the Dockerfile. Catch the mismatch
# here rather than ten minutes into a build.
check-context:
	@set -e; \
	test -f ../.dockerignore || { \
		echo "  ✗ ../.dockerignore is missing — the whole workspace would be uploaded"; exit 1; }; \
	missing=""; \
	for d in $$(grep -E '^COPY ' Dockerfile | grep -v -- '--from=' \
	            | awk '{print $$2}' | cut -d/ -f1 | sort -u); do \
		grep -qx "!$$d" ../.dockerignore || missing="$$missing $$d"; \
	done; \
	if [ -n "$$missing" ]; then \
		echo "  ✗ the Dockerfile COPYs these, but ../.dockerignore does not allowlist them:$$missing"; \
		echo "    add a '!<dir>' line for each — without it the build fails with"; \
		echo "    'Local lib eighttrigrams/<name> not found: /opt/<name>'"; \
		exit 1; \
	fi; \
	for d in $$(grep -E '^!' ../.dockerignore | sed 's/^!//'); do \
		grep -qE "^COPY $$d/" Dockerfile || \
			echo "  ! ../.dockerignore allowlists '$$d' but the Dockerfile never COPYs it (dead upload cost)"; \
	done; \
	echo "  ✓ .dockerignore allowlists every app the Dockerfile COPYs"

preflight: check-context
	@set -e; \
	for r in plurama blog tracker personalist treina music; do \
		dir=".."; if [ "$$r" = "plurama" ]; then dir="."; else dir="../$$r"; fi; \
		echo "Preflight: $$r"; \
		branch=$$(git -C "$$dir" rev-parse --abbrev-ref HEAD); \
		if [ "$$branch" != "main" ]; then echo "  ✗ on branch '$$branch', not main"; exit 1; fi; \
		if [ -n "$$(git -C "$$dir" status --porcelain)" ]; then echo "  ✗ working tree not clean"; exit 1; fi; \
		git -C "$$dir" fetch --quiet origin main; \
		local_sha=$$(git -C "$$dir" rev-parse @); \
		remote_sha=$$(git -C "$$dir" rev-parse @{u}); \
		if [ "$$local_sha" != "$$remote_sha" ]; then echo "  ✗ not in sync with origin/main"; exit 1; fi; \
		echo "  ✓ main, clean, in sync"; \
	done

deploy: preflight test backup
	@test -s mail.yaml || { echo "Missing or empty plurama/mail.yaml"; exit 1; }
	@mkdir -p .build
	@cp ../claude-stuff/plugins/tracker/skills/tracker-api/SKILL.md .build/tracker-api.md
	cd .. && fly deploy --config plurama/fly.toml --dockerfile plurama/Dockerfile

backup-replay-blog:
	@if [ -d ../blog/data ]; then \
		echo "Error: ../blog/data already exists. Remove it first."; \
		exit 1; \
	fi
	@LATEST=$$(ls -t ../backups/plurama-data.*.tar.gz 2>/dev/null | head -1); \
	if [ -z "$$LATEST" ]; then echo "Error: no backup found in ../backups/. Run 'make backup' first."; exit 1; fi; \
	echo "Replaying blog.db from $$LATEST to ../blog/data/ ..."; \
	tar -xzf "$$LATEST" -C ../blog --strip-components=1 app/data/blog.db && \
	echo "Done."

backup-replay-personalist:
	@if [ -d ../personalist/data ]; then \
		echo "Error: ../personalist/data already exists. Remove it first."; \
		exit 1; \
	fi
	@LATEST=$$(ls -t ../backups/plurama-data.*.tar.gz 2>/dev/null | head -1); \
	if [ -z "$$LATEST" ]; then echo "Error: no backup found in ../backups/. Run 'make backup' first."; exit 1; fi; \
	echo "Replaying personalist.db from $$LATEST to ../personalist/data/ ..."; \
	tar -xzf "$$LATEST" -C ../personalist --strip-components=1 app/data/personalist.db && \
	echo "Done."

backup-replay-tracker:
	@if [ -d ../tracker/data ]; then \
		echo "Error: ../tracker/data already exists. Remove it first."; \
		exit 1; \
	fi
	@LATEST=$$(ls -t ../backups/plurama-data.*.tar.gz 2>/dev/null | head -1); \
	if [ -z "$$LATEST" ]; then echo "Error: no backup found in ../backups/. Run 'make backup' first."; exit 1; fi; \
	echo "Replaying tracker.db from $$LATEST to ../tracker/data/ ..."; \
	tar -xzf "$$LATEST" -C ../tracker --strip-components=1 app/data/tracker.db && \
	echo "Done."

clean:
	rm -rf target
