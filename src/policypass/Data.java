package policypass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 政策库与内置数据(截止时间 / 官方来源 / 六类企业模板)。
 */
public final class Data {

    /** 各政策的申报截止时间(示例数据;null 表示常年受理)。 */
    private static final String[][] DEADLINES = {
            {"P001", "2026-10-28"}, {"P002", "2027-06-30"}, {"P003", "2027-04-15"}, {"P004", "2027-03-31"},
            {"P005", null}, {"P006", "2026-11-05"}, {"P007", null}, {"P008", "2027-05-15"},
            {"P009", "2026-12-10"}, {"P010", "2027-06-30"}, {"P011", "2027-08-31"}, {"P012", "2027-09-30"},
            {"P013", "2027-07-31"}, {"P014", null}, {"P015", null}, {"P016", "2027-10-31"},
    };

    /** 各政策的官方来源。kind: doc = 已核验原文页, portal = 官方平台/官网入口。 */
    private static final String[][] SOURCES = {
            {"P001", "工信部 · 优质中小企业梯度培育平台", "https://zjtx.miit.gov.cn/", "portal"},
            {"P002", "国务院公报 · 高新技术企业认定管理办法", "https://www.gov.cn/gongbao/content/2016/content_5076985.htm", "doc"},
            {"P003", "工信部 · 优质中小企业梯度培育平台", "https://zjtx.miit.gov.cn/", "portal"},
            {"P004", "工业和信息化部", "https://www.miit.gov.cn", "portal"},
            {"P005", "工信部 · 优质中小企业梯度培育平台", "https://zjtx.miit.gov.cn/", "portal"},
            {"P006", "济南市工业和信息化局", "https://jnjxw.jinan.gov.cn", "portal"},
            {"P007", "工信部 · 优质中小企业梯度培育平台", "https://zjtx.miit.gov.cn/", "portal"},
            {"P008", "工业和信息化部", "https://www.miit.gov.cn", "portal"},
            {"P009", "工业和信息化部", "https://www.miit.gov.cn", "portal"},
            {"P010", "工业和信息化部", "https://www.miit.gov.cn", "portal"},
            {"P011", "工业和信息化部", "https://www.miit.gov.cn", "portal"},
            {"P012", "工业和信息化部", "https://www.miit.gov.cn", "portal"},
            {"P013", "工业和信息化部", "https://www.miit.gov.cn", "portal"},
            {"P014", "工业和信息化部", "https://www.miit.gov.cn", "portal"},
            {"P015", "财政部 税务总局公告2023年第7号", "https://guangdong.chinatax.gov.cn/gdsw/zjsw_gkwj/2023-03/30/content_1a76647b55764fb4bf8b30dfac70c564.shtml", "doc"},
            {"P016", "山东省市场监督管理局", "https://amr.shandong.gov.cn", "portal"},
    };

    private final Map<String, Object> lib;

    public Data(Map<String, Object> lib) {
        this.lib = lib;
    }

    public Map<String, Object> lib() {
        return lib;
    }

    /* ------------------------------------------------------------------ */
    /* 加载                                                                */
    /* ------------------------------------------------------------------ */

    public static Data load() {
        String text = readText("policies.json");
        if (text == null) {
            throw new IllegalStateException("未找到 policies.json(请在项目根目录运行)");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> lib = (Map<String, Object>) Json.parse(text);
        injectLabels(lib);
        return new Data(lib);
    }

    public static Data load(String path) {
        try {
            String text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> lib = (Map<String, Object>) Json.parse(text);
            injectLabels(lib);
            return new Data(lib);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取政策库: " + path + " (" + e.getMessage() + ")");
        }
    }

    /**
     * 给每条条件注入中文 label(与 Python 版 load_library 一致)。
     * 优先用虚拟字段名,其次用字段定义里的 label,最后回落到字段名。
     */
    @SuppressWarnings("unchecked")
    private static void injectLabels(Map<String, Object> lib) {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        Object fieldsObj = lib.get("fields");
        if (fieldsObj instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) fieldsObj).entrySet()) {
                Object v = e.getValue();
                if (v instanceof Map) {
                    Object l = ((Map<?, ?>) v).get("label");
                    if (l != null) labels.put(e.getKey(), String.valueOf(l));
                }
            }
        }
        labels.put("rd_ratio", "研发费用占营业收入比例");

        for (Object p0 : Engine.list(lib.get("policies"))) {
            Map<String, Object> pol = (Map<String, Object>) p0;
            for (Object c0 : Engine.list(pol.get("conditions"))) {
                Map<String, Object> c = (Map<String, Object>) c0;
                Object cur = c.get("label");
                if (cur == null || String.valueOf(cur).isEmpty()) {
                    String lbl = labels.get(String.valueOf(c.get("field")));
                    c.put("label", lbl != null ? lbl : c.get("field"));
                }
            }
        }
    }

    /** 依次在当前目录、上级目录、classpath 中查找资源文件。 */
    public static String readText(String name) {
        List<Path> candidates = new ArrayList<Path>();
        candidates.add(Paths.get(name));
        candidates.add(Paths.get("..", name));
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                try {
                    return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    // 继续尝试下一个
                }
            }
        }
        java.io.InputStream in = Data.class.getClassLoader().getResourceAsStream(name);
        if (in == null) in = Data.class.getResourceAsStream("/" + name);
        if (in != null) {
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // 落到返回 null
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* 对外数据                                                            */
    /* ------------------------------------------------------------------ */

    private static Map<String, String> deadlineMap() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (String[] row : DEADLINES) m.put(row[0], row[1]);
        return m;
    }

    private static Map<String, Map<String, Object>> sourceMap() {
        Map<String, Map<String, Object>> m = new LinkedHashMap<String, Map<String, Object>>();
        for (String[] row : SOURCES) {
            m.put(row[0], Engine.map("name", row[1], "url", row[2], "kind", row[3]));
        }
        return m;
    }

    /** 政策库(附带截止时间与官方来源),供前端渲染。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> policiesPayload() {
        Map<String, String> dl = deadlineMap();
        Map<String, Map<String, Object>> src = sourceMap();
        List<Object> policies = new ArrayList<Object>();
        for (Object o : Engine.list(lib.get("policies"))) {
            Map<String, Object> p = new LinkedHashMap<String, Object>((Map<String, Object>) o);
            p.put("deadline", dl.get(String.valueOf(p.get("id"))));
            p.put("source", src.get(String.valueOf(p.get("id"))));
            policies.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("meta", lib.get("meta"));
        out.put("fields", lib.get("fields"));
        out.put("policies", policies);
        return out;
    }

    /** 六类企业模板(带预期结果,由引擎实时算出)。 */
    public List<Object> presetsPayload() {
        List<Object> out = new ArrayList<Object>();
        for (Map<String, Object> p : PRESETS) {
            @SuppressWarnings("unchecked")
            Map<String, Object> profile = (Map<String, Object>) p.get("profile");
            Map<String, Object> r = Engine.match(lib, profile);
            Map<String, Object> sum = asMap(r.get("summary"));
            Map<String, Object> pos = asMap(r.get("position"));

            Map<String, Object> expect = new LinkedHashMap<String, Object>();
            expect.put("eligible", sum.get("eligible"));
            expect.put("potential", sum.get("potential"));
            expect.put("ineligible", sum.get("ineligible"));
            expect.put("benefit", sum.get("estimated_benefit"));
            expect.put("tier_label", pos.get("label"));

            Map<String, Object> item = new LinkedHashMap<String, Object>(p);
            item.put("expect", expect);
            out.add(item);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> sampleProfile() {
        Map<String, Object> p = (Map<String, Object>) PRESETS.get(1).get("profile");
        return new LinkedHashMap<String, Object>(p);
    }

    /* ------------------------------------------------------------------ */
    /* 企业档案默认值                                                       */
    /* ------------------------------------------------------------------ */

    public static Map<String, Object> profile(Object... overrides) {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("name", "");
        p.put("industry", "通用设备制造");
        p.put("region", "济南");
        p.put("founded_year", Integer.valueOf(2015));
        p.put("employees", Integer.valueOf(0));
        p.put("scale", "规上");
        p.put("annual_revenue", Integer.valueOf(0));
        p.put("rd_expense", Integer.valueOf(0));
        p.put("tax_total", Integer.valueOf(0));
        p.put("rd_staff_ratio", Integer.valueOf(0));
        p.put("high_tech", Boolean.FALSE);
        p.put("rd_institution", "无");
        p.put("specialized_sme", Boolean.FALSE);
        p.put("tech_sme", Boolean.FALSE);
        p.put("invention_patents", Integer.valueOf(0));
        p.put("equipment_investment", Integer.valueOf(0));
        p.put("main_product_share", Integer.valueOf(0));
        p.put("legal_violation", Boolean.FALSE);
        p.put("iso9001", Boolean.FALSE);
        p.put("green_factory", "无");
        p.put("digital_workshop", Boolean.FALSE);
        p.put("first_set", Boolean.FALSE);
        p.put("integration_mgmt", Boolean.FALSE);
        p.put("industrial_design_center", "无");
        for (int k = 0; k + 1 < overrides.length; k += 2) {
            p.put((String) overrides[k], overrides[k + 1]);
        }
        return p;
    }

    /* ------------------------------------------------------------------ */
    /* 六类企业模板                                                        */
    /* ------------------------------------------------------------------ */

    private static Map<String, Object> preset(String id, String title, String subtitle, String who, String focus,
                                              Map<String, Object> profile) {
        return Engine.map(
                "id", id, "title", title, "subtitle", subtitle, "who", who, "focus", focus, "profile", profile);
    }

    public static final List<Map<String, Object>> PRESETS;

    static {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();

        list.add(preset("early", "初创期 · 小微起步", "营收 800 万 · 规下 · 2021 年成立",
                "想用最低门槛拿到第一笔政策支持的小微企业",
                "低门槛入口(创新型中小企业 / 科技型中小 / 研发加计扣除)",
                profile(
                        "name", "临沂某金属制品厂", "industry", "金属制品", "region", "临沂",
                        "founded_year", Integer.valueOf(2021), "employees", Integer.valueOf(35), "scale", "规下",
                        "annual_revenue", Integer.valueOf(800), "rd_expense", Double.valueOf(9.6),
                        "tax_total", Integer.valueOf(12), "rd_staff_ratio", Integer.valueOf(6),
                        "high_tech", Boolean.FALSE, "rd_institution", "无",
                        "specialized_sme", Boolean.FALSE, "tech_sme", Boolean.FALSE,
                        "invention_patents", Integer.valueOf(0), "equipment_investment", Integer.valueOf(120),
                        "main_product_share", Integer.valueOf(5), "legal_violation", Boolean.FALSE,
                        "iso9001", Boolean.TRUE)));

        list.add(preset("growth", "成长期 · 精密制造", "营收 4600 万 · 规上 · 研发强度 2.39%",
                "营收已上规模、但资质仍空白的成长型制造企业",
                "能力型资质专利(专精特新 / 高新技术企业 / 瞪羚)",
                profile(
                        "name", "济南某精密机械制造有限公司", "industry", "通用设备制造", "region", "济南",
                        "founded_year", Integer.valueOf(2015), "employees", Integer.valueOf(180), "scale", "规上",
                        "annual_revenue", Integer.valueOf(4600), "rd_expense", Integer.valueOf(110),
                        "tax_total", Integer.valueOf(210), "rd_staff_ratio", Integer.valueOf(12),
                        "high_tech", Boolean.FALSE, "rd_institution", "市级",
                        "specialized_sme", Boolean.FALSE, "tech_sme", Boolean.FALSE,
                        "invention_patents", Integer.valueOf(1), "equipment_investment", Integer.valueOf(620),
                        "main_product_share", Integer.valueOf(15), "legal_violation", Boolean.FALSE,
                        "iso9001", Boolean.TRUE, "green_factory", "市级",
                        "industrial_design_center", "市级")));

        list.add(preset("tech", "资质齐全 · 电子信息", "营收 3200 万 · 高新 + 专精特新 + 高研发",
                "研发投入强、资质已齐备的科技型中小企业",
                "精益叠加(绿色工厂 / 数字化车间 / 首台套)",
                profile(
                        "name", "青岛某电子科技有限公司", "industry", "电子信息", "region", "青岛",
                        "founded_year", Integer.valueOf(2018), "employees", Integer.valueOf(120), "scale", "规上",
                        "annual_revenue", Integer.valueOf(3200), "rd_expense", Integer.valueOf(240),
                        "tax_total", Integer.valueOf(180), "rd_staff_ratio", Integer.valueOf(28),
                        "high_tech", Boolean.TRUE, "rd_institution", "省级",
                        "specialized_sme", Boolean.TRUE, "tech_sme", Boolean.TRUE,
                        "invention_patents", Integer.valueOf(4), "equipment_investment", Integer.valueOf(300),
                        "main_product_share", Integer.valueOf(12), "legal_violation", Boolean.FALSE,
                        "iso9001", Boolean.TRUE, "green_factory", "无",
                        "digital_workshop", Boolean.TRUE, "first_set", Boolean.TRUE,
                        "integration_mgmt", Boolean.TRUE, "industrial_design_center", "省级")));

        list.add(preset("leader", "龙头期 · 汽车零部件", "营收 2.6 亿 · 国家级研发机构",
                "体量领先、要冲国家级资质的行业龙头企业",
                "国家级资质(小巨人 / 单项冠军 / 质量奖)",
                profile(
                        "name", "潍坊某汽车零部件集团", "industry", "汽车零部件", "region", "潍坊",
                        "founded_year", Integer.valueOf(2010), "employees", Integer.valueOf(620), "scale", "规上",
                        "annual_revenue", Integer.valueOf(26000), "rd_expense", Integer.valueOf(936),
                        "tax_total", Integer.valueOf(1400), "rd_staff_ratio", Integer.valueOf(15),
                        "high_tech", Boolean.TRUE, "rd_institution", "国家级",
                        "specialized_sme", Boolean.TRUE, "tech_sme", Boolean.FALSE,
                        "invention_patents", Integer.valueOf(8), "equipment_investment", Integer.valueOf(1200),
                        "main_product_share", Integer.valueOf(22), "legal_violation", Boolean.FALSE,
                        "iso9001", Boolean.TRUE, "green_factory", "省级",
                        "digital_workshop", Boolean.TRUE, "first_set", Boolean.FALSE,
                        "integration_mgmt", Boolean.TRUE, "industrial_design_center", "省级")));

        list.add(preset("green", "绿色转型 · 新材料", "营收 8000 万 · 国家级绿色工厂",
                "要拿绿色 / 技改类奖补的流程型制造企业",
                "绿色与技改奖补(绿色工厂 / 技术改造 / 首台套)",
                profile(
                        "name", "淄博某新材料股份有限公司", "industry", "新材料", "region", "淄博",
                        "founded_year", Integer.valueOf(2012), "employees", Integer.valueOf(300), "scale", "规上",
                        "annual_revenue", Integer.valueOf(8000), "rd_expense", Integer.valueOf(256),
                        "tax_total", Integer.valueOf(400), "rd_staff_ratio", Integer.valueOf(11),
                        "high_tech", Boolean.FALSE, "rd_institution", "省级",
                        "specialized_sme", Boolean.TRUE, "tech_sme", Boolean.TRUE,
                        "invention_patents", Integer.valueOf(3), "equipment_investment", Integer.valueOf(900),
                        "main_product_share", Integer.valueOf(8), "legal_violation", Boolean.FALSE,
                        "iso9001", Boolean.TRUE, "green_factory", "国家级",
                        "digital_workshop", Boolean.TRUE, "first_set", Boolean.FALSE,
                        "integration_mgmt", Boolean.TRUE)));

        list.add(preset("risk", "合规风险 · 金属制品", "营收 6000 万 · 存在安全处罚记录",
                "经营尚可、但有合规瑕疵需要先排雷的企业",
                "先排雷再申报(合规风险必须先解决)",
                profile(
                        "name", "济宁某金属制品有限公司", "industry", "金属制品", "region", "济宁",
                        "founded_year", Integer.valueOf(2014), "employees", Integer.valueOf(260), "scale", "规上",
                        "annual_revenue", Integer.valueOf(6000), "rd_expense", Integer.valueOf(240),
                        "tax_total", Integer.valueOf(300), "rd_staff_ratio", Integer.valueOf(13),
                        "high_tech", Boolean.FALSE, "rd_institution", "市级",
                        "specialized_sme", Boolean.FALSE, "tech_sme", Boolean.FALSE,
                        "invention_patents", Integer.valueOf(2), "equipment_investment", Integer.valueOf(700),
                        "main_product_share", Integer.valueOf(10), "legal_violation", Boolean.TRUE,
                        "iso9001", Boolean.TRUE, "green_factory", "市级")));

        PRESETS = list;
    }
}
