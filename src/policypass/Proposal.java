package policypass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 申报书初稿生成器(模板约束式,不调用大模型)。
 *
 * 与 Python 版 proposal.py 一一对应:六章结构 + 逐条条款对照表,
 * 缺信息的位置统一标记「(待补充)」,绝不编造事实。
 */
public final class Proposal {

    public static final String PENDING = "(待补充)";

    private static final String[][] BASIC_FIELDS = {
            {"企业名称", "name"}, {"所属行业", "industry"}, {"注册地区", "region"}, {"成立年份", "founded_year"},
            {"参保人数", "employees"}, {"企业规模", "scale"}, {"上年度营业收入", "annual_revenue"},
            {"上年度研发费用", "rd_expense"}, {"上年度纳税总额", "tax_total"}, {"研发人员占比", "rd_staff_ratio"},
            {"研发机构等级", "rd_institution"}, {"发明专利数量", "invention_patents"},
            {"主导产品细分市场占有率", "main_product_share"}, {"近两年技改设备投入", "equipment_investment"},
    };

    private static final Map<String, String> UNIT_MAP = new LinkedHashMap<String, String>();

    static {
        UNIT_MAP.put("employees", " 人");
        UNIT_MAP.put("annual_revenue", " 万元");
        UNIT_MAP.put("rd_expense", " 万元");
        UNIT_MAP.put("tax_total", " 万元");
        UNIT_MAP.put("rd_staff_ratio", "%");
        UNIT_MAP.put("invention_patents", " 项");
        UNIT_MAP.put("main_product_share", "%");
        UNIT_MAP.put("equipment_investment", " 万元");
    }

    private Proposal() {
    }

    static String fval(Object v, String field) {
        if (v == null) return PENDING;
        if (v instanceof Boolean) return ((Boolean) v).booleanValue() ? "是" : "否";
        if (v instanceof Double) {
            String unit = UNIT_MAP.get(field);
            return Engine.fmt((Double) v) + (unit == null ? "" : unit);
        }
        if (v instanceof Number) {
            String unit = UNIT_MAP.get(field);
            return ((Number) v).toString() + (unit == null ? "" : unit);
        }
        String s = String.valueOf(v);
        if (s.isEmpty()) return PENDING;
        return s;
    }

    static List<String> qualifications(Map<String, Object> profile) {
        List<String> items = new ArrayList<String>();
        if (Engine.bool(profile, "high_tech")) items.add("高新技术企业");
        if (Engine.bool(profile, "specialized_sme")) items.add("专精特新中小企业");
        if (Engine.bool(profile, "tech_sme")) items.add("科技型中小企业");
        if (Engine.bool(profile, "iso9001")) items.add("ISO9001 质量管理体系认证");
        String gf = Engine.str(profile, "green_factory");
        if (gf != null && !gf.isEmpty() && !"无".equals(gf)) items.add("绿色工厂(" + gf + ")");
        String dc = Engine.str(profile, "industrial_design_center");
        if (dc != null && !dc.isEmpty() && !"无".equals(dc)) items.add("工业设计中心(" + dc + ")");
        if (Engine.bool(profile, "digital_workshop")) items.add("数字化车间/智能工厂");
        if (Engine.bool(profile, "first_set")) items.add("首台(套)重大技术装备");
        if (Engine.bool(profile, "integration_mgmt")) items.add("两化融合管理体系贯标");
        return items;
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> build(Map<String, Object> lib,
                                            Map<String, Object> profile,
                                            String policyId) {
        Map<String, Object> policy = null;
        for (Object o : Engine.list(lib.get("policies"))) {
            Map<String, Object> p = (Map<String, Object>) o;
            if (policyId != null && policyId.equals(String.valueOf(p.get("id")))) {
                policy = p;
                break;
            }
        }
        if (policy == null) {
            throw new IllegalArgumentException("未知政策:" + policyId);
        }

        int year = Engine.currentYear();
        Map<String, Double> derived = new LinkedHashMap<String, Double>();
        Double ratio = Engine.rdRatio(profile);
        derived.put("rd_ratio", ratio);

        List<Object> rows = new ArrayList<Object>();
        int passedCount = 0;
        for (Object c0 : Engine.list(policy.get("conditions"))) {
            Map<String, Object> cond = (Map<String, Object>) c0;
            Engine.CondResult r = Engine.evalCond(cond, profile, derived, year);
            if (r.ok) passedCount++;

            String label = cond.get("label") == null ? String.valueOf(cond.get("field")) : String.valueOf(cond.get("label"));
            String actualText;
            if (r.actual == null) {
                actualText = PENDING;
            } else if ("YearLimit".equals(cond.get("type"))) {
                actualText = "成立 " + ((int) Math.floor(((Number) r.actual).doubleValue())) + " 年";
            } else if ("BoolReq".equals(cond.get("type"))) {
                actualText = label + ":" + (Boolean.TRUE.equals(r.actual) ? "是" : "否");
            } else if (r.actual instanceof Boolean) {
                actualText = ((Boolean) r.actual).booleanValue() ? "是" : "否";
            } else if (r.actual instanceof Number) {
                String u = cond.get("unit") == null ? "" : String.valueOf(cond.get("unit"));
                actualText = Engine.fmt(((Number) r.actual).doubleValue()) + ("%".equals(u) ? "" : " ") + u;
            } else {
                actualText = String.valueOf(r.actual);
            }

            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("clause", cond.get("clause") == null ? "" : cond.get("clause"));
            row.put("requirement", cond.get("text") == null ? "" : cond.get("text"));
            row.put("actual", actualText);
            row.put("verdict", r.ok ? "满足" : "不满足");
            row.put("label", label);
            row.put("ok", Boolean.valueOf(r.ok));
            row.put("message", r.message);
            rows.add(row);
        }

        List<Object> warnings = new ArrayList<Object>();
        int totalConds = Engine.list(policy.get("conditions")).size();
        if (passedCount < totalConds) {
            warnings.add("本政策的 " + totalConds + " 条条件中仍有 " + (totalConds - passedCount) + " 条未满足,请补齐后再提交。");
        }

        String ratioText = ratio == null ? PENDING : String.format("%.2f%%", Engine.round2(ratio.doubleValue()));
        List<String> q = qualifications(profile);
        String qualText = q.isEmpty() ? "暂无(待补充)" : join(q, "、");

        List<Object> sections = new ArrayList<Object>();
        sections.add(Engine.map("kind", "quote",
                "text", "本初稿由系统依据企业档案与政策条款自动生成:判断与文本组装均不经过大模型。凡标记「(待补充)」处,请由企业提供真实材料后填写;切勿填写无法佐证的内容。"));
        sections.add(Engine.map("kind", "heading", "level", Integer.valueOf(2), "text", "一、企业基本情况"));

        List<Object> basicRows = new ArrayList<Object>();
        for (String[] f : BASIC_FIELDS) {
            List<Object> row = new ArrayList<Object>();
            row.add(f[0]);
            row.add(fval(profile.get(f[1]), f[1]));
            basicRows.add(row);
        }
        sections.add(Engine.map("kind", "table", "head", Engine.list(new Object[]{"项目", "内容"}), "rows", basicRows));
        sections.add(Engine.map("kind", "para",
                "text", "研发费用占营业收入比例:" + ratioText + "(由「上年度研发费用 ÷ 上年度营业收入」计算得出,非企业自行填报)。"));
        sections.add(Engine.map("kind", "heading", "level", Integer.valueOf(3), "text", "已取得的资质与认定"));
        sections.add(Engine.map("kind", "para", "text", qualText));
        sections.add(Engine.map("kind", "heading", "level", Integer.valueOf(2), "text", "二、符合本政策申报条件的逐条对照"));

        List<Object> condRows = new ArrayList<Object>();
        for (Object o : rows) {
            Map<String, Object> r = (Map<String, Object>) o;
            List<Object> row = new ArrayList<Object>();
            row.add(r.get("clause"));
            row.add(r.get("requirement"));
            row.add(r.get("actual"));
            row.add(r.get("verdict"));
            condRows.add(row);
        }
        sections.add(Engine.map("kind", "table",
                "head", Engine.list(new Object[]{"条款", "申报条件", "企业实际", "结论"}), "rows", condRows));
        sections.add(Engine.map("kind", "para",
                "text", "本政策共 " + totalConds + " 条可机检条件,企业实际满足 " + passedCount + " 条。"));

        if (!warnings.isEmpty()) {
            sections.add(Engine.map("kind", "quote", "text", join(asStrings(warnings), " ")));
        }

        List<Object> rdItems = new ArrayList<Object>();
        rdItems.add("研发机构等级:" + fval(profile.get("rd_institution"), "rd_institution"));
        rdItems.add("发明专利数量:" + fval(profile.get("invention_patents"), "invention_patents"));
        rdItems.add("研发人员占比:" + fval(profile.get("rd_staff_ratio"), "rd_staff_ratio"));
        rdItems.add("上年度研发费用:" + fval(profile.get("rd_expense"), "rd_expense") + "(占营业收入 " + ratioText + ")");
        rdItems.add("已获资质:" + qualText);

        sections.add(Engine.map("kind", "heading", "level", Integer.valueOf(2), "text", "三、研发与创新能力"));
        sections.add(Engine.map("kind", "list", "items", rdItems));
        sections.add(pending("建议补充:研发项目清单、核心技术说明、知识产权证书编号、研发人员名单"));

        sections.add(Engine.map("kind", "heading", "level", Integer.valueOf(2), "text", "四、主要产品与市场"));
        List<Object> prodItems = new ArrayList<Object>();
        prodItems.add("主导产品细分市场占有率:" + fval(profile.get("main_product_share"), "main_product_share"));
        prodItems.add("所属行业:" + fval(profile.get("industry"), "industry"));
        sections.add(Engine.map("kind", "list", "items", prodItems));
        sections.add(pending("建议补充:主要产品型号与用途、前五大客户、市场排名或第三方报告等佐证材料"));

        sections.add(Engine.map("kind", "heading", "level", Integer.valueOf(2), "text", "五、未来发展规划"));
        sections.add(pending("建议补充:未来三年发展规划、拟实施的技术改造或研发项目、预期投入与产出"));

        sections.add(Engine.map("kind", "heading", "level", Integer.valueOf(2), "text", "六、承诺与声明"));
        sections.add(Engine.map("kind", "para",
                "text", "本企业承诺:本次申报所提交的全部材料真实、准确、完整,不存在伪造、篡改或冒用他人成果的情形;如经核查与事实不符,本企业愿承担相应法律责任。"));
        sections.add(Engine.map("kind", "para", "text", "企业名称(盖章):" + fval(profile.get("name"), "name")));
        sections.add(Engine.map("kind", "para", "text", "法定代表人(签字):__________"));
        sections.add(Engine.map("kind", "para", "text", "日期:______年____月____日"));
        sections.add(Engine.map("kind", "heading", "level", Integer.valueOf(3), "text", "附:本初稿依据的条款清单"));

        List<Object> clauseItems = new ArrayList<Object>();
        for (Object o : rows) {
            Map<String, Object> r = (Map<String, Object>) o;
            clauseItems.add(String.valueOf(r.get("clause")) + " " + String.valueOf(r.get("requirement")));
        }
        sections.add(Engine.map("kind", "list", "items", clauseItems));

        int pendingCount = 0;
        for (Object o : sections) {
            Map<String, Object> s = (Map<String, Object>) o;
            if ("para".equals(s.get("kind")) && Boolean.TRUE.equals(s.get("pending"))) pendingCount++;
        }

        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        String stamp = java.time.LocalDateTime.now().format(fmt);

        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("generated_at", stamp);
        meta.put("mode", "模板约束式生成");
        meta.put("ai_in_judgement", Boolean.FALSE);
        meta.put("ai_in_writing", Boolean.FALSE);
        meta.put("policy_id", policy.get("id"));
        meta.put("policy_name", policy.get("name"));
        meta.put("passed", Integer.valueOf(passedCount));
        meta.put("total", Integer.valueOf(totalConds));
        meta.put("pending_count", Integer.valueOf(pendingCount));

        Map<String, Object> policyOut = new LinkedHashMap<String, Object>();
        policyOut.put("id", policy.get("id"));
        policyOut.put("name", policy.get("name"));
        policyOut.put("level", policy.get("level") == null ? "" : policy.get("level"));
        policyOut.put("category", policy.get("category") == null ? "" : policy.get("category"));
        policyOut.put("benefit", policy.get("benefit") == null ? new LinkedHashMap<String, Object>() : policy.get("benefit"));

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("policy", policyOut);
        out.put("meta", meta);
        out.put("sections", sections);
        out.put("markdown", renderMarkdown(policy, meta, sections));
        out.put("filename", policy.get("id") + "-" + policy.get("name") + "-申请书初稿.md");
        out.put("warnings", warnings);
        return out;
    }

    private static Map<String, Object> pending(String hint) {
        Map<String, Object> m = Engine.map("kind", "para", "text", PENDING);
        m.put("pending", Boolean.TRUE);
        m.put("hint", hint);
        return m;
    }

    private static List<String> asStrings(List<Object> items) {
        List<String> out = new ArrayList<String>();
        for (Object o : items) out.add(String.valueOf(o));
        return out;
    }

    @SuppressWarnings("unchecked")
    static String renderMarkdown(Map<String, Object> policy, Map<String, Object> meta, List<Object> sections) {
        StringBuilder b = new StringBuilder();
        b.append("# ").append(policy.get("name")).append(" 申请书(初稿)\n\n");
        b.append("> 生成时间:").append(meta.get("generated_at"))
                .append(" · 生成方式:").append(meta.get("mode"))
                .append(" · 判断与文本组装均不经过大模型\n\n");

        for (Object o : sections) {
            Map<String, Object> sec = (Map<String, Object>) o;
            String kind = String.valueOf(sec.get("kind"));
            if ("heading".equals(kind)) {
                int level = sec.get("level") instanceof Number ? ((Number) sec.get("level")).intValue() : 2;
                b.append(level >= 3 ? "### " : "## ").append(sec.get("text")).append("\n\n");
            } else if ("quote".equals(kind)) {
                b.append("> ").append(sec.get("text")).append("\n\n");
            } else if ("para".equals(kind)) {
                b.append(sec.get("text")).append("\n");
                if (sec.get("hint") != null) b.append("  _").append(sec.get("hint")).append("_\n");
                b.append("\n");
            } else if ("list".equals(kind)) {
                for (Object it : Engine.list(sec.get("items"))) {
                    b.append("- ").append(it).append("\n");
                }
                b.append("\n");
            } else if ("table".equals(kind)) {
                List<Object> head = Engine.list(sec.get("head"));
                b.append("| ");
                for (int k = 0; k < head.size(); k++) {
                    if (k > 0) b.append(" | ");
                    b.append(head.get(k));
                }
                b.append(" |\n|");
                for (int k = 0; k < head.size(); k++) b.append(" --- |");
                b.append("\n");
                for (Object r0 : Engine.list(sec.get("rows"))) {
                    List<Object> row = (List<Object>) r0;
                    b.append("| ");
                    for (int k = 0; k < row.size(); k++) {
                        if (k > 0) b.append(" | ");
                        b.append(row.get(k));
                    }
                    b.append(" |\n");
                }
                b.append("\n");
            }
        }

        b.append("---\n\n");
        b.append("本初稿由「政合我意」依据政策条款与企业档案生成,不构成申报建议;提交前请补齐所有「(待补充)」内容并由政策研究人员复核。");
        return b.toString();
    }
}
