REFORGE_JADX_HOME ?= $(if $(JADX_HOME),$(JADX_HOME),/opt/jadx)
JAVAC := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/javac,javac)
CLASSES := build/classes
SOURCES := $(wildcard *.java)

.PHONY: all build
all: build

build:
	mkdir -p $(CLASSES)
	"$(JAVAC)" --release 21 -encoding UTF-8 -cp "$(REFORGE_JADX_HOME)/lib/*" -d $(CLASSES) $(SOURCES)
