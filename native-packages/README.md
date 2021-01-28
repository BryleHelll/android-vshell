## Native packages build environment

There are located build scripts and patches for compiling native executables
like QEMU and dependencies. Environment is being used as Docker image
containing the Ubuntu distribution, Android NDK standalone toolchain and
number of other utilities used during the build process.

Dockerfile: `./scripts/Dockerfile`

Container setup script: `./scripts/run-docker.sh`
