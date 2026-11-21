package anomaly;

/**
 * Один след разрушения. Не список блоков, а правило: «пятно мёртвой земли радиусом 12»,
 * «трещина длиной 80 под углом 1.2», «пик высотой 60». Блоки считаются по месту, когда до них
 * дошла очередь, — поэтому план маленький, переживает рестарт и не зависит от высоты рельефа.
 */
public final class ScarOp {
	public String type = "dead_soil";
	public double x;
	public double z;
	public double angle;
	public double radius = 8.0;
	public int length = 40;
	public int width = 1;
	public int depth = 2;
	public int height = 40;
	public long seed;
	/** Оценка стоимости в блоках: по ней планировщик держится в бюджете. */
	public int blocks;

	public ScarOp() {
	}

	public static ScarOp disk(String type, double x, double z, double radius, long seed) {
		ScarOp op = new ScarOp();
		op.type = type;
		op.x = x;
		op.z = z;
		op.radius = radius;
		op.seed = seed;
		op.blocks = (int) Math.round(Math.PI * radius * radius);
		return op;
	}

	public static ScarOp line(String type, double x, double z, double angle, int length, int width, int depth,
			long seed) {
		ScarOp op = new ScarOp();
		op.type = type;
		op.x = x;
		op.z = z;
		op.angle = angle;
		op.length = length;
		op.width = width;
		op.depth = depth;
		op.seed = seed;
		op.blocks = length * Math.max(1, width) * (depth + 1);
		return op;
	}

	public static ScarOp spike(double x, double z, double radius, int height, long seed) {
		ScarOp op = disk("spike", x, z, radius, seed);
		op.height = height;
		// реальная высота колонки ~0.5·h из-за спада к краю, а не h/3: иначе бюджет занижен в 1.5 раза
		op.blocks = (int) Math.round(Math.PI * radius * radius * height * 0.55);
		return op;
	}

	/** Провал: круг radius и глубина depth. Не воронка от хлопка, а настоящая дыра в рельефе. */
	public static ScarOp sinkhole(double x, double z, double radius, int depth, long seed) {
		ScarOp op = disk("sinkhole", x, z, radius, seed);
		op.depth = Math.max(1, Math.min(10, depth));
		op.blocks = (int) Math.round(Math.PI * radius * radius * op.depth);
		return op;
	}

	public boolean isLine() {
		return "crack".equals(type) || "lava_fault".equals(type);
	}

	/** Сколько индексов перебрать; часть вернёт null (квадрат вокруг круга). */
	public int columnCount() {
		if (isLine()) {
			return Math.max(1, length) * Math.max(1, width);
		}
		int side = side();
		return side * side;
	}

	private int side() {
		return (int) Math.ceil(radius) * 2 + 1;
	}

	/** Колонка по индексу или null, если индекс попал в угол квадрата. */
	public int[] column(int index) {
		int baseX = (int) Math.floor(x);
		int baseZ = (int) Math.floor(z);
		if (isLine()) {
			int along = index / Math.max(1, width);
			int across = index % Math.max(1, width);
			// ломаная: смещение поперёк зависит от пройденного пути, а не от индекса ширины
			double jag = Math.sin(along * 0.35 + seed * 0.0001) * Math.min(2.5, Math.max(1.0, length * 0.02));
			double offset = across - (width - 1) * 0.5 + jag;
			double cos = Math.cos(angle);
			double sin = Math.sin(angle);
			return new int[]{
					(int) Math.floor(baseX + cos * along - sin * offset),
					(int) Math.floor(baseZ + sin * along + cos * offset)
			};
		}
		int side = side();
		int ix = index % side - (side - 1) / 2;
		int iz = index / side - (side - 1) / 2;
		if (ix * ix + iz * iz > radius * radius) {
			return null;
		}
		return new int[]{baseX + ix, baseZ + iz};
	}

	public String describe() {
		return switch (type) {
			case "spike" -> "пик " + Math.round(height) + " (r " + Math.round(radius) + ")";
			case "sinkhole" -> "провал r " + Math.round(radius) + " × " + depth;
			case "crack", "lava_fault" -> (type.equals("lava_fault") ? "лавовый разлом " : "трещина ")
					+ length + "×" + width;
			default -> type + " r " + Math.round(radius);
		};
	}
}
