.PHONY: start stop build deploy clean

start:
	@DEV=true clj -X:run

stop:
	@pkill -f 'plurama.server' || true

build:
	clj -T:build uber

deploy:
	cd .. && fly deploy --config plurama/fly.toml --dockerfile plurama/Dockerfile

clean:
	rm -rf target
