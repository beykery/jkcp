# jkcp

kcp for java . base on netty .

kcp是一种独立于底层通信协议的重传算法，jkcp直接构建于udp之上
并提供方便的编程接口，只需要继承相关的类即可；用户不用关心udp
和kcp的使用细节就能轻松驾驭视频、moba类等需要高速传输环境的应用开发

## 坐标

```xml
  <dependency>
      <groupId>org.beykery</groupId>
      <artifactId>jkcp</artifactId>
      <version>1.3.0</version>
  </dependency>
```

## 使用

请参考src/test/java/test目录下的TestServer和TestClient

## kcp的算法细节请参考

[kcp](https://github.com/skywind3000/kcp)
