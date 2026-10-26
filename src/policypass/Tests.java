package policypass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动化自检:判定结果、可复现性、申报书结构、来源注入。
 * 直接运行:java -cp out policypass.Tests
 */
public final class Tests {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<String>();

    private static void check(String name, Runnable body) {
        try {
            body.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable t) {
            failures.add(name + " -> " + t.getMessage());
            System.out.println("[FAIL] " + name + " -> " + t.getMessage());
        }
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new RuntimeException(msg);
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        boolean eq = expected == null ? actual == null : expected.equals(actual);
        if (!eq) throw new RuntimeException(msg + " (期望 " + expected + ", 实际 " + actual + ")");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>();
    }

    private static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<Object>();
    }

    private static double dbl(Object o) {
        Double d = Engine.num(o);
        return d == null ? 0.0 : d.doubleValue();
    }

    public static void main(String[] args) {
        final Data data = Data.load();

        check("A01 政策库加载 16 项", new Runnable() {
            public void run() {
                assertEquals(16, asList(data.lib().get("policies")).size(), "政策数");
            }
        });

        check("A02 条件总数 57 条", new Runnable() {
            public void run() {
                int n = 0;
                for (Object p : asList(data.lib().get("policies"))) n += asList(asMap(p).get("conditions")).size();
                assertEquals(57, n, "条件数");
            }
        });

        check("A03 档案字段 24 个", new Runnable() {
            public void run() {
                assertEquals(24, asMap(data.lib().get("fields")).size(), "字段数");
            }
        });

        check("A04 六类企业模板", new Runnable() {
            public void run() {
                assertEquals(6, Data.PRESETS.size(), "模板数");
            }
        });

        check("B01 研发强度由引擎计算(110/4600)", new Runnable() {
            public void run() {
                Map<String, Object> p = new LinkedHashMap<String, Object>(asMap(Data.PRESETS.get(1).get("profile")));
                p.put("rd_ratio", Double.valueOf(99.0)); // 试图干扰
                assertEquals(2.39, Math.rint(Engine.rdRatio(p).doubleValue() * 100) / 100.0, "研发强度");
            }
        });

        check("C01 成长期示例:4/8/4, 奖补 72", new Runnable() {
            public void run() {
                Map<String, Object> r = Engine.match(data.lib(), data.sampleProfile());
                Map<String, Object> s = asMap(r.get("summary"));
                assertEquals(4, s.get("eligible"), "可申报数");
                assertEquals(8, s.get("potential"), "潜力项数");
                assertEquals(4, s.get("ineligible"), "不符合数");
                assertEquals(72.0, dbl(s.get("estimated_benefit")), "奖补合计");
            }
        });

        check("C02 企业定位=成长期", new Runnable() {
            public void run() {
                Map<String, Object> r = Engine.match(data.lib(), data.sampleProfile());
                assertEquals("成长期", asMap(r.get("position")).get("label"), "定位");
            }
        });

        check("C03 最接近政策 P001", new Runnable() {
            public void run() {
                Map<String, Object> r = Engine.match(data.lib(), data.sampleProfile());
                assertEquals("P001", asMap(r.get("closest")).get("id"), "最近政策");
            }
        });

        check("D01 同一输入两次结果完全一致", new Runnable() {
            public void run() {
                String a = Json.stringify(Engine.match(data.lib(), data.sampleProfile()));
                String b = Json.stringify(Engine.match(data.lib(), data.sampleProfile()));
                assertEquals(a, b, "可复现性");
            }
        });

        check("E01 六类模板的预期与实算一致", new Runnable() {
            public void run() {
                for (Object o : data.presetsPayload()) {
                    Map<String, Object> item = asMap(o);
                    Map<String, Object> expect = asMap(item.get("expect"));
                    Map<String, Object> r = Engine.match(data.lib(), asMap(item.get("profile")));
                    Map<String, Object> sum = asMap(r.get("summary"));
                    assertEquals(expect.get("eligible"), sum.get("eligible"), item.get("id") + " 可申报");
                    assertEquals(expect.get("potential"), sum.get("potential"), item.get("id") + " 潜力");
                    assertEquals(expect.get("ineligible"), sum.get("ineligible"), item.get("id") + " 不符合");
                }
            }
        });

        check("F01 空档案不抛异常,全部可判定", new Runnable() {
            public void run() {
                Map<String, Object> r = Engine.match(data.lib(), Data.profile());
                assertEquals(16.0, dbl(asMap(r.get("summary")).get("total")), "总数");
            }
        });

        check("H01 申报书六章结构", new Runnable() {
            public void run() {
                Map<String, Object> prop = Proposal.build(data.lib(), data.sampleProfile(), "P009");
                int h2 = 0;
                for (Object o : asList(prop.get("sections"))) {
                    Map<String, Object> s = asMap(o);
                    if ("heading".equals(s.get("kind")) && dbl(s.get("level")) == 2) h2++;
                }
                assertEquals(6, h2, "二级标题数");
            }
        });

        check("H02 申报书含两张表", new Runnable() {
            public void run() {
                Map<String, Object> prop = Proposal.build(data.lib(), data.sampleProfile(), "P009");
                int tables = 0;
                for (Object o : asList(prop.get("sections"))) {
                    if ("table".equals(asMap(o).get("kind"))) tables++;
                }
                assertEquals(2, tables, "表格数");
            }
        });

        check("H03 待补充标记 3 处", new Runnable() {
            public void run() {
                Map<String, Object> prop = Proposal.build(data.lib(), data.sampleProfile(), "P009");
                assertEquals(3, asMap(prop.get("meta")).get("pending_count"), "待补充数");
            }
        });

        check("H04 markdown 含政策名与免责声明", new Runnable() {
            public void run() {
                Map<String, Object> prop = Proposal.build(data.lib(), data.sampleProfile(), "P009");
                String md = String.valueOf(prop.get("markdown"));
                assertTrue(md.contains("申请书(初稿)"), "缺标题");
                assertTrue(md.contains("不构成申报建议"), "缺免责声明");
            }
        });

        check("H05 申报书确定性(除时间戳外一致)", new Runnable() {
            public void run() {
                String a = String.valueOf(Proposal.build(data.lib(), data.sampleProfile(), "P009").get("markdown"));
                String b = String.valueOf(Proposal.build(data.lib(), data.sampleProfile(), "P009").get("markdown"));
                assertEquals(stripStamp(a), stripStamp(b), "确定性");
            }
        });

        check("H06 每项政策都有来源(name/url/kind)", new Runnable() {
            public void run() {
                int docs = 0;
                for (Object o : asList(data.policiesPayload().get("policies"))) {
                    Map<String, Object> src = asMap(asMap(o).get("source"));
                    assertTrue(src.get("name") != null && src.get("url") != null, "缺来源: " + asMap(o).get("id"));
                    String kind = String.valueOf(src.get("kind"));
                    assertTrue("doc".equals(kind) || "portal".equals(kind), "来源类型非法");
                    if ("doc".equals(kind)) docs++;
                }
                assertTrue(docs >= 1, "应至少有一条已核验原文");
            }
        });

        check("H07 截止时间注入(至少 10 项)", new Runnable() {
            public void run() {
                int withDl = 0;
                for (Object o : asList(data.policiesPayload().get("policies"))) {
                    if (asMap(o).get("deadline") != null) withDl++;
                }
                assertTrue(withDl >= 10, "带截止时间的政策不足");
            }
        });

        check("H08 未知政策抛出明确错误", new Runnable() {
            public void run() {
                try {
                    Proposal.build(data.lib(), data.sampleProfile(), "P999");
                    throw new RuntimeException("应当抛异常");
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage().contains("P999"), "错误信息应含政策号");
                }
            }
        });

        System.out.println("------------------------------------------------------------");
        System.out.println("结果: " + passed + " / " + (passed + failures.size()) + " 通过");
        if (!failures.isEmpty()) {
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过。");
    }

    /** 去掉生成时间那一行,便于比较确定性。 */
    private static String stripStamp(String md) {
        StringBuilder b = new StringBuilder();
        for (String line : md.split("\n", -1)) {
            if (line.startsWith("> 生成时间:")) continue;
            b.append(line).append('\n');
        }
        return b.toString();
    }
}
