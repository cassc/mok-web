<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
**Table of Contents**  *generated with [DocToc](https://github.com/thlorenz/doctoc)*

- [mok](#mok)
  - [依赖](#%E4%BE%9D%E8%B5%96)
  - [关于centos 7 防火墙](#%E5%85%B3%E4%BA%8Ecentos-7-%E9%98%B2%E7%81%AB%E5%A2%99)
  - [编译、打包及运行](#%E7%BC%96%E8%AF%91%E3%80%81%E6%89%93%E5%8C%85%E5%8F%8A%E8%BF%90%E8%A1%8C)
  - [停止服务器](#%E5%81%9C%E6%AD%A2%E6%9C%8D%E5%8A%A1%E5%99%A8)
  - [配置文件](#%E9%85%8D%E7%BD%AE%E6%96%87%E4%BB%B6)
- [JPUSH推送](#jpush%E6%8E%A8%E9%80%81)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

# mok

OKOK健康平台后台管理。

> 测试用户及密码`admin/admin`
> 对于PUT/POST请求，客户端可使用json或form/multipart请求。使用json请求时，header的`content-type`必须为`Content-Type:text/plain;charset=UTF-8`

## 依赖
> 编译与运行环境均需要JDK8+JCE/Redis/MySQL/Sparrows
> 编译环境还需配置Leinigen
> 运行环境还需配置MySQL/ImageMagick

* JDK8 + [JCE](http://stackoverflow.com/questions/6481627/java-security-illegal-key-size-or-default-parameters/6481658#6481658)
```bash
tar -xzvf jdk-8u5-linux-x64.tar.gz
sudo mkdir -p /usr/lib/jvm/
sudo mv jdk-8u5-linux-x64 /usr/lib/jvm/jdk8

sudo update-alternatives --install "/usr/bin/java" "java" "/usr/lib/jvm/jdk8/bin/java" 1
sudo update-alternatives --install "/usr/bin/javac" "javac" "/usr/lib/jvm/jdk8/bin/javac" 1
sudo update-alternatives --install "/usr/bin/javaws" "javaws" "/usr/lib/jvm/jdk8/bin/javaws" 1

sudo update-alternatives --config javac
sudo update-alternatives --config java

# 安装JCE
# 解压`jce_policy-8.zip$`并覆盖到`{java.home}/jre/lib/security/`
sudo cp  UnlimitedJCEPolicyJDK8/* /usr/lib/jvm/jdk8/jre/lib/security/
```

* [leinigen](https://github.com/technomancy/leiningen), [lein下载地址](https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein)

```bash
mkdir ~/bin
mkdir ~/.lein
cp lein ~/bin/
cp self-installs ~/.lein/
# 安装好后下面的命令会自动下载依赖包：
lein
```

* sparrows

在sparrows的源码目录执行，
```bash
lein install
``` 
也可在本地安装后，使用本地的`~/.m2/repository/sparrows/sparrows/`目录覆盖远程的相同目录。

* redis

在官网下载源码包，使用`make`, `make install`安装。

Centos 7上如果出现`zmalloc.h:50:31: fatal error: jemalloc/jemalloc.h: No such file or directory`错误，使用如下命令编译

```bash
tar -xzvf redis-3.0.3.tar.gz
cd redis-3.0.3
sudo yum install gcc make
cd deps
make hiredis jemalloc linenoise lua
cd ..
make
sudo make install
```

  * 配置`redis.conf`,更改或添加以下几个参数

```conf
daemonize yes
# 只允许本机访问
bind 127.0.0.1
# 密码
requirepass dtCck-g2jRR94anZRip2WedWhRFMo-zsBX5cta-5KT-KdFfEMuqDHaMX-y7DpFxKXKto9pEShuhXEpxfoAA-eDiDdYDGXkfoXCVxwNadslfasjlcxSn2gLAcEcCbMZuUjKi5mNtkzWnUraVznKKuCPEMGm2HpCF5P_UXGgfHSALDUa3c9YPkQXAgwyKxD3y4nVoLADK46Hsasdfsa102834851kjdshYgwAgkv-rxL6kA7T5xZwPA7L2-rhRU97Dh_Q53yE2GbQHYxJK5UJrNFjzdqdD9ocfvBMUM9pqgz3Hg6kgeBqkeEkWMch
dir /opt/rds/data
``` 
  * 运行redis: `redis-server redis.conf`

* ImageMagick 使用系统自带包管理器安装:
```bash
sudo yum search imagemagick
sudo yum install ImageMagick.x86_64
```

## 关于centos 7 防火墙
[关闭防火墙](http://www.liquidweb.com/kb/how-to-stop-and-disable-firewalld-on-centos-7/) 或[开放端口](http://stackoverflow.com/questions/24729024/centos-7-open-firewall-port)

开放端口的方法：
```bash
# 查看区
sudo firewall-cmd --get-active-zones
# 返回如下
#public
#  interfaces: ens160
# 开放7890端口
sudo firewall-cmd --zone=public --add-port=7890/tcp --permanent
sudo firewall-cmd --reload
```



## 编译、打包及运行

> 编译环境需配置JDK+JCE/Leinigen
> 运行环境需配置JDK+JCE/Redis/MySQL/ImageMagick

* 打包mok：`lein do clean, uberjar`或`lein do clean, ring uberjar`，前者使用`http-kit`服务器，后者使用`jetty`容器。打包时会生成两个jar包，文件较大的才是我们需要的。
* 使用下面的脚本运行
```bash
#!/bin/bash
cd /path/to/mok/
env MOK_CONFIG=remote-config-mok.edn java -jar -server -Xms768m -Xmx768m -Xmn400m -XX:+AggressiveOpts -XX:+UseBiasedLocking -XX:MaxMetaspaceSize=128m -XX:+DisableExplicitGC -XX:LargePageSizeInBytes=4m -XX:+UseCMSInitiatingOccupancyOnly -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/mok-dump.hprof -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=10 -XX:GCLogFileSize=100M -Xloggc:/tmp/mok-jvm-gc.log -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=10 -XX:+UseConcMarkSweepGC -XX:+CMSParallelRemarkEnabled  mok-standalone.jar &
```
说明：
 * 将`/path/to/mok/`替换为`mok-standalone.jar`的所在目录的路径。
 * `env MOK_CONFIG=remote-config-mok.edn`表示使用`remote-config-vhs.edn`作为配置文件启动，而非默认的`config-mok.edn`。
 * `-server ...`为JVM参数。以上为分配约(768+128)M内存给mok程序时的配置。
* 配置文件与返回码文件（`codes.edn`）应与`mok-standalone.jar`在同一目录。

## 停止服务器

使用`kill [pid]`的方式停止mok服务器。

```bash
# 查看mok的PID
ps aux |grep mo[k]
# 返回如下 
<!-- lixun    22829 93.7  4.8 2489040 90832 pts/1   Sl   14:00   0:06 java -Xbootclasspath/a:/home/lixun/.lein/self-installs/leiningen-2.5.0-standalone.jar -Dfile.encoding=UTF-8 -Dmaven.wagon.http.ssl.easy=false -Dmaven.wagon.rto=10000 -XX:+TieredCompilation -XX:TieredStopAtLevel=1 -Dleiningen.original.pwd=/home/lixun/projects/mok -Dleiningen.script=/home/lixun/bin/lein -classpath /home/lixun/.lein/self-installs/leiningen-2.5.0-standalone.jar clojure.main -m leiningen.core.main ring server -->
kill 22829
```

`kill-mok.sh`示例
```bash
#!/bin/bash
set -- $(ps aux|grep mo[k])
PID=$2
kill -7 $PID
```


## 配置文件
参考`config-mok.edn`


# JPUSH推送

> 支持按客户端平台（android/ios）、用户ID、第三方公司ID推送。
> JPUSH iOS推送同时使用APN与TCP连接，Android端使用TCP连接。也就是说Android端后台进程被关闭后，无法即时收到推送。离线消息保留1天（可配置）。

服务器依据tag进行推送，所以在客户端能收到消息前，客户端需要先调用JPush的API方法设置tag。参考[Android设置别名](http://docs.jpush.io/client/android_api/#api_1)

```java
public static void setAliasAndTags(Context context, 
                                   String alias, 
                                   Set<String> tags, 
                                   TagAliasCallback callback)
```

`alias`设为`cuid_c_u`。其中`c`替换为comopanyid，`u`替换为用户的手机号，如`cuid_1_19874563211`

`tags`须包含以下几组：
* `companyid_n`: 其中`n`替换为当前用户的companyid
* `sex_n`: 其中`n`替换为当前用户的sex (1:M 2:F)
* `platform_s`：其中`s`替换为当前APP的平台(android/ios)
* `version_n`：其中`n`替换为当前APP的版本号

开发/测试环境APP还须注册tag
* `testing_n`: 其中n为内网主机IP最后一个字段

正式环境APP还须注册tag
* `prod_` 

待定的`tags`：
* `loc_gd`: 其中`gd`替换为当前省份的首字母缩写（由服务器返回）
* `age_n`: 其中`n`替换为当前用户的年龄对应的组



测试环境推送时，还应注册`testing_n`,其中`n`替换为主机IP最后一位，如`testing_70`


客户端收到的消息中的`alert`为显示在手机顶部的消息提示，`extras`为JSon字符串，格式如下，其中的`uri`为消息内容的下载地址，可以是txt或html格式。


```json
...
        "extras": {
            "uri": "1440402206093/f85549cdfce6dde492e26783e84e4dea.html`,
            "title": "测试中",
            "categories": "1,2"
            "ts": 1440402206093
            "preview": true
        },
```


其中
* `categories`： 1:减肥 2:运动 3:饮食 4:母婴 5:健康贴士
* `title` 显示在提示栏的文字
* `uri` 指向资源的路径. 使用host+uri访问，如`http://localhost:7890/message/1440729759254/8c32b1f76c746d784f0c1fd005e2a220.html`
* `ts` 指消息发送时间
* `preview` boolean类型，true表示预览消息


