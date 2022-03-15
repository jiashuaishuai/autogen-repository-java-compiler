# autogen-repository-java-compiler
### 自动生成RD项目Request 和Repository的apt插件
### 使用方法
引入插件

```groovy
    implementation 'com.github.jiashuaishuai:autogen-repository-annotation:1.0.1'
    kapt 'com.github.jiashuaishuai:autogen-repository-java-compiler:1.0.0'
```

api 接口文件添加注解

```java
@Autogen(HOST)
public interface ApiService {}
```
**Autogen注解只能够修饰interfac**

rebuild后自动扫描被注解修饰类内的所有方法，生成相应代码，使用：继承AbsXXXXXRepositor类


