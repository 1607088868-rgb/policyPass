package policypass;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性政策资格匹配引擎。
 *
 * 与 Python 版 engine.py 逻辑一一对应:判断过程不经过任何大模型,
 * 同样输入永远同样输出;每条结论均带条款号与原文。
 */
public final class Engine {

    public static final String ELIGIBLE = "eligible";
    public static final String POTENTIAL = "potential";
    public static final String INELIGIBLE = "ineligible";

    private static final String[] STATUS_ORDER = {ELIGIBLE, POTENTIAL, INELIGIBLE};
    private static final Map<String, String> STATUS_LABEL = new LinkedHashMap<String, String>();

    static {
        STATUS_LABEL.put(ELIGIBLE, "可以申报");
        STATUS_LABEL.put(POTENTIAL, "潜力项");
        STATUS_LABEL.put(INELIGIBLE, "暂不符合");
    }

    private Engine() {
    }

    /* ------------------------------------------------------------------ */
    /* 基础工具                                                            */
    /* ------------------------------------------------------------------ */

    /** 便捷构造有序 Map。 */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int k = 0; k + 1 < kv.length; k += 2) {
            m.put((String) kv[k], kv[k + 1]);
        }
        return m;
    }

    public static Double num(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            String t = ((String) v).trim();
            if (t.isEmpty()) return null;
            try {
                return Double.valueOf(t);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 数字格式化:整数不带小数点,其余保留至多两位。 */
    public static String fmt(Double v) {
        if (v == null) return "—";
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        double r = Math.rint(v * 100) / 100.0;
        return String.valueOf(r);
    }

    public static double round2(double v) {
        return Math.rint(v * 100) / 100.0;
    }

    /** 研发强度 = 研发费用 / 营业收入 × 100(不采信手填比例)。 */
    public static Double rdRatio(Map<String, Object> profile) {
        Double rev = num(profile.get("annual_revenue"));
        Double rd = num(profile.get("rd_expense"));
        if (rev == null || rev <= 0 || rd == null) return null;
        return rd / rev * 100.0;
    }

    public static int currentYear() {
        return Calendar.getInstance().get(Calendar.YEAR);
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static boolean bool(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Boolean && (Boolean) v;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        if (o instanceof List) return (List<Object>) o;
        if (o instanceof Object[]) {
            List<Object> out = new ArrayList<Object>();
            for (Object x : (Object[]) o) out.add(x);
            return out;
        }
        return new ArrayList<Object>();
    }

    /* ------------------------------------------------------------------ */
    /* 单条件求值                                                          */
    /* ------------------------------------------------------------------ */

    public static final class CondResult {
        public final boolean ok;
        public final Object actual;
        public final String message;

        CondResult(boolean ok, Object actual, String message) {
            this.ok = ok;
            this.actual = actual;
            this.message = message;
        }
    }

    public static CondResult evalCond(Map<String, Object> cond,
                                      Map<String, Object> profile,
                                      Map<String, Double> derived,
                                      int year) {
        String field = str(cond, "field");
        String label = cond.get("label") == null ? field : String.valueOf(cond.get("label"));
        String clause = cond.get("clause") == null ? "" : String.valueOf(cond.get("clause"));
        String desc = cond.get("desc") == null ? "" : String.valueOf(cond.get("desc"));
        String type = str(cond, "type");

        Object actual;
        if (derived != null && derived.containsKey(field)) {
            actual = derived.get(field);
        } else {
            actual = profile.get(field);
        }

        if ("NumRange".equals(type)) {
            Double low = num(cond.get("min"));
            Double high = num(cond.get("max"));
            String unit = cond.get("unit") == null ? "" : String.valueOf(cond.get("unit"));
            Double av = num(actual);
            if (av == null) {
                return new CondResult(false, null, "「" + label + "」未填写,无法判定(" + clause + ")");
            }
            boolean ok = true;
            if (low != null && av < low) ok = false;
            if (high != null && av > high) ok = false;
            if (ok) return new CondResult(true, av, "");
            return new CondResult(false, av,
                    label + "为 " + fmt(av) + unit + ",不满足「" + desc + "」要求(" + clause + ")");
        }

        if ("BoolReq".equals(type)) {
            boolean required = Boolean.TRUE.equals(cond.get("required"));
            boolean av = Boolean.TRUE.equals(actual);
            if (av == required) return new CondResult(true, Boolean.valueOf(av), "");
            return new CondResult(false, Boolean.valueOf(av),
                    label + "不满足「" + desc + "」要求(" + clause + ")");
        }

        if ("EnumIn".equals(type)) {
            List<Object> allowed = list(cond.get("allowed"));
            boolean ok = false;
            for (Object a : allowed) {
                if (a == null ? actual == null : a.equals(actual) || String.valueOf(a).equals(String.valueOf(actual))) {
                    ok = true;
                    break;
                }
            }
            if (ok) return new CondResult(true, actual, "");
            StringBuilder sb = new StringBuilder();
            for (Object a : allowed) {
                if (sb.length() > 0) sb.append(" / ");
                sb.append(String.valueOf(a));
            }
            return new CondResult(false, actual,
                    label + "为「" + (actual == null ? "未填写" : String.valueOf(actual)) + "」,不在允许范围「"
                            + sb + "」内(" + clause + ")");
        }

        if ("YearLimit".equals(type)) {
            Double needD = num(cond.get("years"));
            int need = needD == null ? 0 : (int) needD.doubleValue();
            Double founded = num(actual);
            if (founded == null || founded <= 0) {
                return new CondResult(false, null, "「" + label + "」未填写,无法判定成立年限(" + clause + ")");
            }
            int age = year - (int) Math.floor(founded.doubleValue());
            if (age >= need) return new CondResult(true, Double.valueOf(age), "");
            return new CondResult(false, Double.valueOf(age),
                    "成立年限为 " + age + " 年,未满 " + need + " 年(" + clause + ")");
        }

        return new CondResult(false, null, "未知条件类型");
    }

    /* ------------------------------------------------------------------ */
    /* 企业定位                                                            */
    /* ------------------------------------------------------------------ */

    private static final Object[][] TIERS = {
            {Double.valueOf(20000), "leader", "龙头期",
                    "营收 2 亿元以上,已具备规模优势。政策重心是国家级资质(小巨人、单项冠军)与税收优惠叠加,而非普惠奖补。"},
            {Double.valueOf(5000), "mature", "规模期",
                    "营收 5000 万–2 亿元,已跨越规上门槛。政策重心是能级跃升:专精特新、小巨人、绿色工厂、数字化车间。"},
            {Double.valueOf(1000), "growth", "成长期",
                    "营收 1000 万–5000 万元,具备规模化潜力。政策重心是能力型资质:专精特新、高新技术企业、瞪羚企业。"},
            {Double.valueOf(0), "early", "初创期",
                    "营收 1000 万元以下,处于打基础阶段。政策重心是低门槛入口:创新型中小企业、科技型中小企业、研发费用加计扣除。"},
    };

    public static Map<String, Object> position(Map<String, Object> profile) {
        Double revD = num(profile.get("annual_revenue"));
        double revenue = revD == null ? 0 : revD.doubleValue();
        Double ratio = rdRatio(profile);
        Double foundedD = num(profile.get("founded_year"));
        Integer age = foundedD == null ? null : Integer.valueOf(currentYear() - (int) Math.floor(foundedD.doubleValue()));

        Object[] tier = TIERS[TIERS.length - 1];
        for (Object[] t : TIERS) {
            if (revenue >= ((Double) t[0]).doubleValue()) {
                tier = t;
                break;
            }
        }

        String rdText;
        String rdKind;
        if (ratio == null) {
            rdText = "研发强度未填";
            rdKind = "muted";
        } else if (ratio.doubleValue() >= 5) {
            rdText = "研发强度 ≥5%(高)";
            rdKind = "high";
        } else if (ratio.doubleValue() >= 2) {
            rdText = "研发强度 2%–5%(中)";
            rdKind = "mid";
        } else {
            rdText = "研发强度 <2%(低)";
            rdKind = "low";
        }

        List<Object> badges = new ArrayList<Object>();
        badges.add(map("text", age == null ? "成立年限未填" : ("成立 " + age + " 年"), "kind", "plain"));
        badges.add(map("text", "营收 " + fmt(Double.valueOf(revenue)) + " 万元", "kind", "plain"));
        badges.add(map("text", rdText, "kind", rdKind));
        badges.add(map("text", "规模" + (str(profile, "scale") == null ? "未填" : str(profile, "scale")), "kind", "plain"));
        if (bool(profile, "high_tech")) badges.add(map("text", "高新技术企业", "kind", "good"));
        if (bool(profile, "specialized_sme")) badges.add(map("text", "专精特新", "kind", "good"));
        if (bool(profile, "tech_sme")) badges.add(map("text", "科技型中小企业", "kind", "good"));
        if (bool(profile, "legal_violation")) badges.add(map("text", "存在合规风险", "kind", "bad"));

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("tier", tier[1]);
        out.put("label", tier[2]);
        out.put("desc", tier[3]);
        out.put("revenue", Double.valueOf(revenue));
        out.put("rd_ratio", ratio);
        out.put("badges", badges);
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* 总匹配                                                              */
    /* ------------------------------------------------------------------ */

    @SuppressWarnings("unchecked")
    public static Map<String, Object> match(Map<String, Object> lib, Map<String, Object> profile) {
        int year = currentYear();
        Map<String, Double> derived = new LinkedHashMap<String, Double>();
        derived.put("rd_ratio", rdRatio(profile));

        List<Object> out = new ArrayList<Object>();

        for (Object p0 : list(lib.get("policies"))) {
            Map<String, Object> pol = (Map<String, Object>) p0;
            List<Object> passed = new ArrayList<Object>();
            List<Object> failed = new ArrayList<Object>();
            List<Object> conds = list(pol.get("conditions"));

            for (int idx = 0; idx < conds.size(); idx++) {
                Map<String, Object> cond = (Map<String, Object>) conds.get(idx);
                CondResult r = evalCond(cond, profile, derived, year);
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("order", Integer.valueOf(idx + 1));
                item.put("id", cond.get("id"));
                item.put("type", cond.get("type"));
                item.put("field", cond.get("field"));
                item.put("label", cond.get("label") == null ? cond.get("field") : cond.get("label"));
                item.put("clause", cond.get("clause") == null ? "" : cond.get("clause"));
                item.put("text", cond.get("text") == null ? "" : cond.get("text"));
                item.put("desc", cond.get("desc") == null ? "" : cond.get("desc"));
                item.put("unit", cond.get("unit") == null ? "" : cond.get("unit"));
                item.put("actual", r.actual);
                item.put("ok", Boolean.valueOf(r.ok));
                item.put("message", r.message);
                if (r.ok) passed.add(item);
                else failed.add(item);
            }

            int n = failed.size();
            String status = n == 0 ? ELIGIBLE : (n == 1 ? POTENTIAL : INELIGIBLE);

            Map<String, Object> gap = null;
            if (POTENTIAL.equals(status) && !failed.isEmpty()) {
                Map<String, Object> only = (Map<String, Object>) failed.get(0);
                Map<String, Object> benefit = (Map<String, Object>) pol.get("benefit");
                gap = new LinkedHashMap<String, Object>();
                gap.put("condition_id", only.get("id"));
                gap.put("clause", only.get("clause"));
                gap.put("text", only.get("text"));
                gap.put("message", only.get("message"));
                gap.put("after_fix", benefit == null || benefit.get("desc") == null ? "" : benefit.get("desc"));
            }

            Map<String, Object> rec = new LinkedHashMap<String, Object>();
            rec.put("id", pol.get("id"));
            rec.put("name", pol.get("name"));
            rec.put("level", pol.get("level") == null ? "" : pol.get("level"));
            rec.put("category", pol.get("category") == null ? "" : pol.get("category"));
            rec.put("summary", pol.get("summary") == null ? "" : pol.get("summary"));
            rec.put("benefit", pol.get("benefit") == null ? new LinkedHashMap<String, Object>() : pol.get("benefit"));
            rec.put("status", status);
            rec.put("status_label", STATUS_LABEL.get(status));
            rec.put("total_conditions", Integer.valueOf(conds.size()));
            rec.put("passed_count", Integer.valueOf(passed.size()));
            rec.put("failed_count", Integer.valueOf(n));
            rec.put("passed", passed);
            rec.put("failed", failed);
            rec.put("gap", gap);
            out.add(rec);
        }

        final List<Object> sorted = new ArrayList<Object>(out);
        java.util.Collections.sort(sorted, new java.util.Comparator<Object>() {
            public int compare(Object a, Object b) {
                int ia = indexOf(STATUS_ORDER, String.valueOf(((Map<?, ?>) a).get("status")));
                int ib = indexOf(STATUS_ORDER, String.valueOf(((Map<?, ?>) b).get("status")));
                return ia - ib;
            }
        });

        Map<String, Object> groups = new LinkedHashMap<String, Object>();
        List<Object> gEligible = new ArrayList<Object>();
        List<Object> gPotential = new ArrayList<Object>();
        List<Object> gIneligible = new ArrayList<Object>();
        for (Object o : sorted) {
            String st = String.valueOf(((Map<?, ?>) o).get("status"));
            if (ELIGIBLE.equals(st)) gEligible.add(o);
            else if (POTENTIAL.equals(st)) gPotential.add(o);
            else gIneligible.add(o);
        }
        groups.put(ELIGIBLE, gEligible);
        groups.put(POTENTIAL, gPotential);
        groups.put(INELIGIBLE, gIneligible);

        Map<String, Object> closest = null;
        for (Object o : sorted) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (POTENTIAL.equals(m.get("status"))) {
                Map<String, Object> g = (Map<String, Object>) m.get("gap");
                closest = new LinkedHashMap<String, Object>();
                closest.put("id", m.get("id"));
                closest.put("name", m.get("name"));
                closest.put("clause", g.get("clause"));
                closest.put("message", g.get("message"));
                closest.put("after_fix", g.get("after_fix"));
                break;
            }
        }

        double benefitSum = 0;
        for (Object o : gEligible) {
            Map<String, Object> m = (Map<String, Object>) o;
            Map<String, Object> b = (Map<String, Object>) m.get("benefit");
            Double amt = b == null ? null : num(b.get("amount"));
            if (amt != null) benefitSum += amt.doubleValue();
        }

        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("total", Integer.valueOf(sorted.size()));
        summary.put("eligible", Integer.valueOf(gEligible.size()));
        summary.put("potential", Integer.valueOf(gPotential.size()));
        summary.put("ineligible", Integer.valueOf(gIneligible.size()));
        summary.put("estimated_benefit", Double.valueOf(round2(benefitSum)));

        Map<String, Object> derivedOut = new LinkedHashMap<String, Object>();
        Double ratio = derived.get("rd_ratio");
        derivedOut.put("rd_ratio", ratio == null ? null : Double.valueOf(round2(ratio.doubleValue())));

        Map<String, Object> engineInfo = new LinkedHashMap<String, Object>();
        engineInfo.put("name", "政合我意确定性匹配引擎");
        engineInfo.put("version", "1.0.0");
        engineInfo.put("year", Integer.valueOf(year));
        engineInfo.put("deterministic", Boolean.TRUE);
        engineInfo.put("ai_in_judgement", Boolean.FALSE);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("engine", engineInfo);
        result.put("profile", profile);
        result.put("derived", derivedOut);
        result.put("position", position(profile));
        result.put("closest", closest);
        result.put("summary", summary);
        result.put("groups", groups);
        result.put("policies", sorted);
        return result;
    }

    private static int indexOf(String[] arr, String v) {
        for (int k = 0; k < arr.length; k++) {
            if (arr[k].equals(v)) return k;
        }
        return arr.length;
    }
}
