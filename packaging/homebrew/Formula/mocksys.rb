class Mocksys < Formula
  desc "Agent-native CLI that turns real API traffic into reusable, scrubbed mock fixtures"
  homepage "https://github.com/chazu/mocksys"
  version "0.0.1"
  license "MIT"

  # mocksys is a self-contained binary, but it drives Mountebank as an external
  # daemon (so a recorded mock survives across separate CLI invocations). Mountebank
  # runs on Node, which we provision into libexec and point mocksys at via MOCKSYS_MB.
  depends_on "node"

  on_macos do
    on_arm do
      url "https://github.com/chazu/mocksys/releases/download/v0.0.1/mocksys-darwin-arm64.tar.gz"
      sha256 "REPLACE_WITH_DARWIN_ARM64_SHA256"
    end
    on_intel do
      url "https://github.com/chazu/mocksys/releases/download/v0.0.1/mocksys-darwin-x64.tar.gz"
      sha256 "REPLACE_WITH_DARWIN_X64_SHA256"
    end
  end

  on_linux do
    on_arm do
      url "https://github.com/chazu/mocksys/releases/download/v0.0.1/mocksys-linux-arm64.tar.gz"
      sha256 "REPLACE_WITH_LINUX_ARM64_SHA256"
    end
    on_intel do
      url "https://github.com/chazu/mocksys/releases/download/v0.0.1/mocksys-linux-x64.tar.gz"
      sha256 "REPLACE_WITH_LINUX_X64_SHA256"
    end
  end

  def install
    # Each release tarball contains a single binary named `mocksys`.
    libexec.install "mocksys"

    # Provision Mountebank (+ its deps) alongside, so mocksys never has to fetch it.
    system "npm", "install", "--prefix", libexec, "mountebank@2.9.1"

    # Wrapper points mocksys at the bundled mb and keeps the user's cwd (so .mocks/
    # and $MOCKSYS_HOME resolve in their project, not here).
    (bin/"mocksys").write <<~SH
      #!/bin/bash
      export MOCKSYS_MB="#{libexec}/node_modules/.bin/mb"
      exec "#{libexec}/mocksys" "$@"
    SH
    chmod 0755, bin/"mocksys"
  end

  test do
    assert_match "agent-native", shell_output("#{bin}/mocksys help")
    # contract -> compile path works fully offline (no daemon needed):
    ENV["MOCKSYS_HOME"] = testpath/"store"
    system bin/"mocksys", "add", "t/ping", "--request", "GET /ping", "--text", "pong"
    assert_match "ping", shell_output("#{bin}/mocksys examples t/ping")
  end
end
