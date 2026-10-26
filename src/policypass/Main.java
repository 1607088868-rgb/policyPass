package policypass;

import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令行入口。
 *
 *   java policypass.Main serve   [--host 0.0.0.0] [--port 8710]
 *   java policypass.Main check   [--preset growth] [--profile my.json] [--json]
 *   java policypass.Main presets
 *   java policypass.Main policies
 *   java policypass.Main proposal P009
 */
public final class Main {

    private static PrintStream out;

    public static void main(String[] args) {
        try {
            out = new PrintStream(System.out, true, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            out = System.out;
        }

        if (args.length == 0) {
            usage();
            return;
        }

        String cmd = args[0];
        try {
            if ("serve".equals(cmd)) {
                Data data = Data.load();
                String host = "0.0.0.0";
                int port = 8710;
                for (int i = 1; i < args.length; i++) {
                    if ("--host".equals(args[i]) && i + 1 < args.length) host = args[++i];
                    else if ("--port".equals(args[i]) && i + 1 < args.length) port = Integer.parseInt(args[++i]);
                }
                new WebServer(data).start(host, port);
                return;
            }
            if ("check".equals(cmd)) {
                Data data = Data.load();
                Map<String, Object> profile = resolveProfile(data, args);
                Map<String, Object> result = Engine.match(data.lib(), profile);
                if (hasFlag(args, "--json")) {
                    out.println(Json.stringify(result));
                } else {
                    printReport(profile, result);
                }
                return;
            }
            if ("presets".equals(cmd)) {
                Data data = Data.load();
                printPresets(data);
                return;
            }
            if ("policies".equals(cmd)) {
                Data data = Data.load();
                printPolicies(data);
                return;
            }
            if ("proposal".equals(cmd)) {
                Data data = Data.load();
                String id = args.length > 1 ? args[1] : "P009";
                Map<String, Object> profile = resolveProfile(data, args);
                Map<String, Object> prop = Proposal.build(data.lib(), profile, id);
                out.println(String.valueOf(prop.get("markdown")));
                return;
            }
            usage();
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("政合我意 · 政策资格体检(Java 版)");
        System.out.println("用法:");
        System.out.println("  java policypass.Main serve [--host 0.0.0.0] [--port 8710]");
        System.out.println("  java policypass.Main check [--preset early|growth|tech|leader|green|risk] [--profile my.json] [--json]");
        System.out.println("  java policypass.Main presets");
        System.out.println("  java policypass.Main policies");
        System.out.println("  java policypass.Main proposal P009");
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) if (flag.equals(a)) return true;
        return false;
    }

    private static String argValue(String[] args, String flag) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (flag.equals(args[i])) return args[i + 1];
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveProfile(Data data, String[] args) throws Exception {
        String preset = argValue(args, "--preset");
        String profilePath = argValue(args, "--profile");
        if (profilePath != null) {
            String text = new String(Files.readAllBytes(Paths.get(profilePath)), StandardCharsets.UTF_8);
            Object parsed = Json.parse(text);
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("profile 必须是 JSON 对象");
            return (Map<String, Object>) parsed;
        }
        if (preset != null) {
            for (Map<String, Object> p : Data.PRESETS) {
                if (preset.equals(p.get("id"))) {
                    return (Map<String, Object>) p.get("profile");
                }
            }
            throw new IllegalArgumentException("未知企业类型: " + preset);
        }
        return data.sampleProfile();
    }

    @SuppressWarnings("unchecked")
    private static void printReport(Map<String, Object> profile, Map<String, Object> result) {
        Map<String, Object> sum = asMap(result.get("summary"));
        Map<String, Object> pos = asMap(result.get("position"));
        Map<String, Object> groups = asMap(result.get("groups"));
        Map<String, Object> engine = asMap(result.get("engine"));
        Map<String, Object> derived = asMap(result.get("derived"));

        out.println("============================================================");
        out.println("  政合我意 · 政策资格体检报告");
        out.println("============================================================");
        out.println("企业:" + nvl(profile.get("name")) + "  (" + nvl(profile.get("industry")) + " / " + nvl(profile.get("region")) + ")");
        out.println("引擎:" + nvl(engine.get("name")) + " v" + nvl(engine.get("version")) + " · 判定不经大模型,结果可复算");
        Object ratio = derived.get("rd_ratio");
        out.println("研发强度:" + (ratio == null ? "—" : Engine.fmt(Engine.num(ratio)) + "%"));
        out.println("");
        out.println("【企业定位】" + nvl(pos.get("label")));
        out.println("  " + nvl(pos.get("desc")));
        out.println("");
        out.println("【体检结论】共 " + nvl(sum.get("total")) + " 项政策");
        out.println("  可申报  " + nvl(sum.get("eligible")) + " 项   潜力项  " + nvl(sum.get("potential"))
                + " 项   暂不符合  " + nvl(sum.get("ineligible")) + " 项");
        out.println("  可申报政策奖补合计约 " + Engine.fmt(Engine.num(sum.get("estimated_benefit"))) + " 万元");
        out.println("");

        printGroup("可以申报", list(groups.get("eligible")), false);
        printGroup("潜力项(只差一条)", list(groups.get("potential")), true);
        printGroup("暂不符合", list(groups.get("ineligible")), false);

        Map<String, Object> closest = asMap(result.get("closest"));
        if (!closest.isEmpty()) {
            out.println("------------------------------------------------------------");
            out.println("【最接近的一项】" + nvl(closest.get("name")));
            out.println("  卡在:" + nvl(closest.get("clause")) + " " + nvl(closest.get("message")));
        }
        out.println("------------------------------------------------------------");
        out.println("说明:结论由确定性规则计算得出,可逐条追溯到政策条款;");
        out.println("      政策条款与截止时间为示例数据,申报前请以主管部门原文为准。");
    }

    private static void printGroup(String title, List<Object> items, boolean showGap) {
        if (items.isEmpty()) return;
        out.println("------------------------------------------------------------");
        out.println("■ " + title + "(" + items.size() + " 项)");
        for (Object o : items) {
            Map<String, Object> p = asMap(o);
            Map<String, Object> b = asMap(p.get("benefit"));
            String amount = b.get("amount") == null ? "" : Engine.fmt(Engine.num(b.get("amount"))) + " 万元";
            out.println("  " + nvl(p.get("id")) + "  " + nvl(p.get("name")) + (amount.isEmpty() ? "" : "  [" + amount + "]"));
            out.println("        " + nvl(p.get("level")) + " / " + nvl(p.get("category"))
                    + "    通过 " + nvl(p.get("passed_count")) + "/" + nvl(p.get("total_conditions")));
            if (showGap) {
                Map<String, Object> gap = asMap(p.get("gap"));
                if (!gap.isEmpty()) {
                    out.println("        待补:" + nvl(gap.get("clause")) + " " + nvl(gap.get("message")));
                }
            }
        }
        out.println("");
    }

    @SuppressWarnings("unchecked")
    private static void printPresets(Data data) {
        out.println("企业类型模板(6 类):");
        for (Map<String, Object> p : Data.PRESETS) {
            Map<String, Object> profile = (Map<String, Object>) p.get("profile");
            Map<String, Object> r = Engine.match(data.lib(), profile);
            Map<String, Object> sum = asMap(r.get("summary"));
            Map<String, Object> pos = asMap(r.get("position"));
            out.println("");
            out.println("  [" + p.get("id") + "] " + p.get("title") + "  —  " + p.get("subtitle"));
            out.println("       " + p.get("who"));
            out.println("       " + p.get("focus"));
            out.println("       预期:" + nvl(pos.get("label")) + " · 可申报 " + nvl(sum.get("eligible"))
                    + " / 潜力 " + nvl(sum.get("potential")) + " / 不符合 " + nvl(sum.get("ineligible"))
                    + " · 奖补约 " + Engine.fmt(Engine.num(sum.get("estimated_benefit"))) + " 万元");
        }
    }

    @SuppressWarnings("unchecked")
    private static void printPolicies(Data data) {
        List<Object> policies = list(data.policiesPayload().get("policies"));
        out.println("政策库(" + policies.size() + " 项):");
        for (Object o : policies) {
            Map<String, Object> p = asMap(o);
            out.println("");
            out.println("  " + p.get("id") + "  " + p.get("name"));
            out.println("       " + p.get("level") + " / " + p.get("category")
                    + " · 截止 " + (p.get("deadline") == null ? "常年受理" : p.get("deadline")));
            out.println("       " + nvl(p.get("summary")));
        }
    }

    private static List<Object> list(Object o) {
        return o instanceof List ? castList(o) : new ArrayList<Object>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object o) {
        return (List<Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>();
    }

    private static String nvl(Object o) {
        return o == null ? "—" : String.valueOf(o);
    }
}
