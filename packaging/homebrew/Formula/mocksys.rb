class Mocksys < Formula
  desc "Agent-native CLI that turns real API traffic into reusable mock fixtures"
  homepage "https://github.com/chazu/mocksys"
  version "0.1.0"
  license "MIT"

  on_macos do
    on_arm do
      url "https://github.com/chazu/mocksys/releases/download/v0.1.0/mocksys-darwin-arm64.tar.gz"
      sha256 "29a58c7cc460f6a26b424ecd9c1ad4a895484e9f090debd8753ab7969732fcd8"
    end
    on_intel do
      url "https://github.com/chazu/mocksys/releases/download/v0.1.0/mocksys-darwin-x64.tar.gz"
      sha256 "2de6bd6729fa0bb0e965883b98905e87195fff9f9d8c9a90ecdfd48b68c41aa7"
    end
  end

  on_linux do
    on_arm do
      url "https://github.com/chazu/mocksys/releases/download/v0.1.0/mocksys-linux-arm64.tar.gz"
      sha256 "e3899399a95d5163984488cb30ecd3447c015120ab32fc89dd1aeec7f2fba4f3"
    end
    on_intel do
      url "https://github.com/chazu/mocksys/releases/download/v0.1.0/mocksys-linux-x64.tar.gz"
      sha256 "ba8f5306f4ff757bb277fa4a1d2173b27ec86f7a4b0701044afb575b20dc69a2"
    end
  end

  def install
    # Each release tarball contains a single binary named `mocksys`. It is the CLI
    # and, as `mocksys daemon`, the runtime that serves the mocks.
    bin.install "mocksys"
  end

  test do
    assert_match "author reusable mock fixtures", shell_output("#{bin}/mocksys help")
    # contract -> compile path works fully offline (no daemon needed):
    ENV["MOCKSYS_HOME"] = testpath/"store"
    system bin/"mocksys", "add", "t/ping", "--request", "GET /ping", "--text", "pong"
    assert_match "ping", shell_output("#{bin}/mocksys examples t/ping")
  end
end
