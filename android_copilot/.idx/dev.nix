# Google Project IDX Configuration File (.idx/dev.nix)
# See https://developers.google.com/idx/guides/customize-idx-env for docs

{ pkgs, ... }: {
  channel = "stable-23.11";

  # 包含 Android SDK 与 JDK 17
  packages = [
    pkgs.jdk17
    pkgs.gradle
    pkgs.git
  ];

  # 启用 Android 模拟器与开发扩展
  idx = {
    extensions = [
      "vscjava.vscode-java-pack"
      "mathiasfrohlich.Kotlin"
    ];

    previews = {
      enable = true;
      previews = {
        android = {
          command = ["./gradlew" "installDebug" "android:start"];
          manager = "android";
        };
      };
    };
  };
}
