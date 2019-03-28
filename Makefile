.PHONY: clean sass build
sass:
	npm run build:css
build: sass
	lein build
sync-files:
	rsync -av --delete resources/public/ nh:/home/haier/projects/mok-web/resources/public/
sync-ncern-files:
	rsync -av --delete resources/public/ ncern:projects/mok-web/resources/public/
deploy: build sync-files
