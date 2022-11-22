# ARCore Cloud Anchors Sample

This is a sample app demonstrating how ARCore anchors can be hosted and resolved
using the ARCore Cloud Service.

## Getting Started

 See [Get started with Cloud Anchors for Android](https://developers.google.com/ar/develop/java/cloud-anchors/cloud-anchors-quickstart-android)
 to learn how to set up your development environment and try out this sample app.


## Single Anchor 동작방식
- `com.google.ar.core` 패키지 안에 `Anchor anchor;`로 선언됨
- `void handleTap()`: 
  - HOST 모드일 때만 `anchor` 생성할 수 있도록 제한이 걸려있음
- `void handleTap()`: `anchor`