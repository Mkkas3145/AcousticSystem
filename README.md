# AcousticSystem

Minecraft 1.21.11, 26.1.2와 26.2용 Fabric 사운드 모드입니다.

## 기능

- 벽이나 블록 뒤의 소리가 자연스럽게 작아집니다.
- 방, 동굴, 야외에서 소리가 다르게 들립니다.
- 막힌 소리도 모서리나 열린 통로를 따라 전달됩니다.
- 물과 용암 속에서는 소리가 해당 환경에 맞게 바뀝니다.
- 블록을 설치하거나 부수면 소리도 바로 달라집니다.
- 블록마다 소리 특성을 JSON 파일로 설정할 수 있습니다.

## 요구 사항

- Minecraft 1.21.11, 26.1.2 또는 26.2
- Fabric Loader 0.19.3 이상
- Minecraft 버전에 맞는 Fabric API
- Minecraft 1.21.11: Java 21 이상
- Minecraft 26.1.2 및 26.2: Java 25 이상

## 빌드

- 26.2: `./gradlew build`
- 26.1.2: `./gradlew buildMinecraft2612`
- 1.21.11: `./gradlew buildMinecraft12111`
- 모두 빌드: `./gradlew buildAllMinecraftVersions`
