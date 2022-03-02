# lan-clip

局域网公共剪贴板

## 为什么开发lan-clip？

我个人的主力开发机包括一台Macbook Pro和一台Win10系统的台式机。很多时候，我需要同时使用这两台电脑，而在两台电脑之间相互复制内容则是一个非常频繁的操作。
比如，我在A电脑上登了社交软件，但我需要将B电脑上的内容截图，并通过社交软件发出。在没有lan-clip之前，这种操作很麻烦。

我了解到登录了iCloud的iPhone和Mac可以使用统一的剪贴板，即我可以在iPhone上复制内容，在Mac上进行粘贴。受此启发，我想到开发一个类似的工具，用于两台不同操作系统的电脑之间。

## 原理简述

本工具使用jvm上的函数式语言Clojure进行开发，由于使用的多数是java的类库，因此和使用java开发区别并不是很大。

JDK中提供了操作系统中剪贴板的API，使得我可以获取到剪贴板上的内容，或者向剪贴板上设置内容。
于是我可以监听某电脑上剪贴板上的内容，如果内容发生了变化，则将该内容通过网络发送给另一台电脑，并将这些内容设置到另一台电脑的剪贴板上。

由于Mac系统上监听剪贴板的API(lostOwnership)存在BUG，为了跨平台使用，本工具使用了每2秒钟（这里应该可以配置）轮循读取剪贴板上的内容，并与缓存的内容进行比较的方式去监听剪贴板。这样的方式效率可能稍微低一些，但是也没有更好的解决办法。

网络连接方面使用了Netty，基于TCP协议进行传输。

### 关于剪贴板

JDK中使用Clipboard这个类来映射操作系统中的剪贴板，剪贴板上的内容常见的可以分为三类：文字、图像和文件列表。
文字就是字符串，这个不用多说了。图像就是屏幕截图等操作时的类型，文件列表就是复制或者剪切文件时的类型。

实际开发过程中，发现各个操作系统对于上述几种类型的映射存在一定的差异。在Windows系统上，内容通常只被映射成唯一的类型，文字就是文字，图像就是图像。
而在MacOS系统上，内容会被映射成所有可能的类型，如复制或剪切图片类型的文件时，它可以同时被映射成文字（文件名）、图像以及文件列表。
为了填补这种差异，我将这几种常见类型规定了优先级，文件列表>图像>文字，这样只取优先级最高的类型，可以保证在各个系统中表现一致。

除了上述差异外，各个操作系统对于图像类型的映射也不完全相同，只能保证读取的内容是java.awt.Image类型的对象（具体的子类型在各个操作系统上不一致）。
而由于输出图像时需要使用的是BufferedImage类型，因此在处理时需要将Image类型转换成BufferedImage类型（如果已经是BuffererImage类型，直接返回即可）。

## 用途

同一局域网内，可以在不同的操作系统之间使用统一的剪贴板。已经在MacOS和Win10系统上进行了验证。

目前暂时支持字符串类型和图像类型的直接复制粘贴，文件类型还没有想好怎么处理。初步打算是文件较小时，直接发送给另一台电脑；文件较大时暂不处理。这个临界值应该可以配置。

## 如何使用？

两台电脑上各启动一个jvm程序即可，可以将本项目打成jar包，然后通过
```shell
java -jar XXX.jar
```
正式版发布后，会提供各个系统的native package。

命令进行启动。注意修改配置文件，确定好本机器使用的端口，以及目标机器的ip和端口。

## 如何开发？

### 开发环境要求
- JDK 11以上版本
- Clojure 1.10.1以上版本
- leiningen 2.9.3以上版本
- 开发工具可以使用Intellij IDEA + Cursive或者Emacs + cider

本项目使用leiningen进行项目管理，可以先在本机安装leiningen，然后在Intellij idea中将项目导入为leiningen项目（需要安装Cursive插件，有免费license）。
也可以使用emacs+cider的方式进行开发。

可以使用以下命令将项目打包为jar包
```shell
lein uberjar
```

可以使用jdk14以上版本所带的jpackage将jar包打成可执行文件，mac系统上为dmg，windows系统上为exe。
在Windows系统上进行打包时，依赖于Wix工具。

## 支持项目/贡献代码

欢迎Star。

欢迎issue和pull request。

## License

Copyright © 2022 Hao Liang

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
