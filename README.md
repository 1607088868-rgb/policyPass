# 政合我意(PolicyPass)· Java 版

给山东中小制造企业做「政策资格体检」的工具:**AI 负责读懂政策,代码负责判断资格**。

本仓库是 Python 原型(见原项目)的 **Java 重写版**,零第三方依赖,只用 JDK 标准库:

- 判定引擎:`Engine`(确定性规则,不含任何大模型调用)
- 申报书生成:`Proposal`(模板约束式,缺信息标「(待补充)」)
- 内置 Web 服务:`com.sun.net.httpserver`(无需 Tomcat / Spring)
- JSON:自带极简解析器(`Json`),不引 fastjson / Jackson

## 快速开始

```bash
# 编译(需要 JDK 8 及以上)
javac -encoding UTF-8 -d out src/policypass/*.java

# 网页版
java -cp out policypass.Main serve --port 8710
# 浏览器打开 http://127.0.0.1:8710

# 命令行体检
java -cp out policypass.Main check
java -cp out policypass.Main check --preset tech
java -cp out policypass.Main check --json

# 六类企业模板 / 政策库 / 申报书
java -cp out policypass.Main presets
java -cp out policypass.Main policies
java -cp out policypass.Main proposal P009

# 自动化自检
java -cp out policypass.Tests
```

Windows 控制台若中文乱码,先执行 `chcp 65001`。

## 目录结构

```
├── src/policypass/
│   ├── Main.java         # 命令行入口:serve / check / presets / policies / proposal
│   ├── WebServer.java    # 内置 HTTP 服务,提供 /api/* 与前端页面
│   ├── Engine.java       # 确定性匹配引擎 + 企业定位
│   ├── Proposal.java     # 申报书初稿生成(六章 + 条款对照表)
│   ├── Data.java         # 政策库加载、截止时间、官方来源、六类企业模板
│   ├── Json.java         # 极简 JSON 解析/序列化(容忍 UTF-8 BOM)
│   └── Tests.java        # 19 项自动化自检
├── policies.json         # 政策库:24 项档案字段 + 16 项政策 / 57 条可机检条件
└── web/index.html        # 前端单页(深浅双主题 / 条款溯源 / 申报日历 / 申报计划)
```

## 核心设计

### 三种判定结果

| 结果 | 含义 |
| --- | --- |
| 可以申报 | 全部条件满足 |
| 潜力项 | 只差 1 条条件 |
| 暂不符合 | 差 2 条及以上 |

每条结论都绑定 **条款号 + 原文**,可逐条追溯。

### 为什么不把判断交给 AI

企业要拿结论去申报、去花钱。同一个问题问两次得到两个答案、或结论不可解释,这个工具就没有价值。
所以:抽取政策条款可以用 AI,**资格判定用确定性代码**——同样输入永远同样输出。

### 研发强度

`rd_ratio` 由 `研发费用 ÷ 营业收入 × 100` 实时计算,**不采信企业自填的比例**。

## 已验证

- 19 项自动化自检全部通过
- 与 Python 原型的**判定结果完全一致**(16 项政策状态、汇总 4/8/4、奖补 72 万元、企业定位、最接近政策)
- 申报书 markdown 与 Python 版**逐字一致**

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` | 前端页面 |
| GET | `/api/health` | 健康检查 |
| GET | `/api/policies` | 政策库(含截止时间与官方来源) |
| GET | `/api/presets` | 六类企业模板(含预期结果) |
| GET | `/api/sample` | 示例企业档案 |
| POST | `/api/match` | 传入企业档案,返回体检结果 |
| POST | `/api/proposal` | 传入 `{profile, policy_id}`,返回申报书初稿 |

## 数据说明

政策库中的**条款号、条款原文、截止时间均为示例数据**,按真实政策类型与申报逻辑构造,未经主管部门文本核对。
正式使用前必须逐条替换为官方原文,并以主管部门认定为准。界面与申报书中均已标注免责声明。
