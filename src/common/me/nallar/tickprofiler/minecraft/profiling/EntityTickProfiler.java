package me.nallar.tickprofiler.minecraft.profiling;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.base.Functions;
import com.google.common.collect.Ordering;

import cpw.mods.fml.common.FMLLog;
import me.nallar.tickprofiler.minecraft.TickProfiler;
import me.nallar.tickprofiler.minecraft.commands.ProfileCommand;
import me.nallar.tickprofiler.util.MappingUtil;
import me.nallar.tickprofiler.util.TableFormatter;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ReportedException;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraftforge.common.ForgeDummyContainer;
import org.cliffc.high_scale_lib.NonBlockingHashMap;

public class EntityTickProfiler {
	public static final EntityTickProfiler ENTITY_TICK_PROFILER = new EntityTickProfiler();
	public static ProfileCommand.ProfilingState profilingState = ProfileCommand.ProfilingState.NONE;
	private int ticks;
	private final AtomicLong totalTime = new AtomicLong();
	private volatile int chunkX;
	private volatile int chunkZ;
	private volatile long startTime;

	private EntityTickProfiler() {
	}

	public static synchronized boolean startProfiling(ProfileCommand.ProfilingState profilingState_) {
		if (profilingState != ProfileCommand.ProfilingState.NONE) {
			return false;
		}
		profilingState = profilingState_;
		return true;
	}

	public static synchronized void endProfiling() {
		profilingState = ProfileCommand.ProfilingState.NONE;
	}

	public void setLocation(final int x, final int z) {
		chunkX = x;
		chunkZ = z;
	}

	public boolean startProfiling(final Runnable runnable, ProfileCommand.ProfilingState state, final int time, final Collection<World> worlds_) {
		if (time <= 0) {
			throw new IllegalArgumentException("time must be > 0");
		}
		final Collection<World> worlds = new ArrayList<World>(worlds_);
		synchronized (EntityTickProfiler.class) {
			if (!startProfiling(state)) {
				return false;
			}
			for (World world_ : worlds) {
				TickProfiler.instance.hookProfiler(world_);
			}
		}

		Runnable profilingRunnable = new Runnable() {
			@Override
			public void run() {
				try {
					Thread.sleep(1000 * time);
				} catch (InterruptedException ignored) {
				}

				synchronized (EntityTickProfiler.class) {
					endProfiling();
					runnable.run();
					clear();
					for (World world_ : worlds) {
						TickProfiler.instance.unhookProfiler(world_);
					}
				}
			}
		};
		Thread profilingThread = new Thread(profilingRunnable);
		profilingThread.setName("TickProfiler");
		profilingThread.start();
		startTime = System.currentTimeMillis();
		return true;
	}

	public void runEntities(World world, ArrayList<Entity> toTick) {
		long end = System.nanoTime();
		long start;
		boolean isGlobal = profilingState == ProfileCommand.ProfilingState.GLOBAL;
		for (int i = 0; i < toTick.size(); i++) {
			Entity entity = toTick.get(i);

			start = end;
			if (entity.ridingEntity != null) {
				if (!entity.ridingEntity.isDead && entity.ridingEntity.riddenByEntity == entity) {
					continue;
				}

				entity.ridingEntity.riddenByEntity = null;
				entity.ridingEntity = null;
			}

			if (!entity.isDead) {
				try {
					world.updateEntity(entity);
				} catch (Throwable var8) {
					CrashReport crashReport = CrashReport.makeCrashReport(var8, "Ticking entity");
					CrashReportCategory crashReportCategory = crashReport.makeCategory("Entity being ticked");
					entity.func_85029_a(crashReportCategory);

					if (ForgeDummyContainer.removeErroringEntities) {
						FMLLog.severe(crashReport.getCompleteReport());
						world.removeEntity(entity);
					} else {
						throw new ReportedException(crashReport);
					}
				}
			}

			if (entity.isDead) {
				int chunkX = entity.chunkCoordX;
				int chunkZ = entity.chunkCoordZ;

				if (entity.addedToChunk && world.getChunkProvider().chunkExists(chunkX, chunkZ)) {
					world.getChunkFromChunkCoords(chunkX, chunkZ).removeEntity(entity);
				}

				if (toTick.size() <= i || toTick.get(i) != entity) {
					toTick.remove(entity);
				} else {
					toTick.remove(i--);
				}
				world.releaseEntitySkin(entity);
			}
			end = System.nanoTime();

			if (isGlobal || (entity.chunkCoordX == chunkX && entity.chunkCoordZ == chunkZ)) {
				record(entity, end - start);
			}
		}
	}

	public void runTileEntities(World world, ArrayList<TileEntity> toTick) {
		IChunkProvider chunkProvider = world.getChunkProvider();
		Iterator<TileEntity> iterator = toTick.iterator();
		long end = System.nanoTime();
		long start;
		boolean isGlobal = profilingState == ProfileCommand.ProfilingState.GLOBAL;
		while (iterator.hasNext()) {
			start = end;
			TileEntity tileEntity = iterator.next();

			int x = tileEntity.xCoord;
			int z = tileEntity.zCoord;
			if (!tileEntity.isInvalid() && tileEntity.func_70309_m() && chunkProvider.chunkExists(x >> 4, z >> 4)) {
				try {
					tileEntity.updateEntity();
				} catch (Throwable var6) {
					CrashReport crashReport = CrashReport.makeCrashReport(var6, "Ticking tile entity");
					CrashReportCategory crashReportCategory = crashReport.makeCategory("Tile entity being ticked");
					tileEntity.func_85027_a(crashReportCategory);
					if (ForgeDummyContainer.removeErroringTileEntities) {
						FMLLog.severe(crashReport.getCompleteReport());
						tileEntity.invalidate();
						world.setBlockToAir(x, tileEntity.yCoord, z);
					} else {
						throw new ReportedException(crashReport);
					}
				}
			}

			if (tileEntity.isInvalid()) {
				iterator.remove();

				if (chunkProvider.chunkExists(tileEntity.xCoord >> 4, tileEntity.zCoord >> 4)) {
					Chunk chunk = world.getChunkFromChunkCoords(tileEntity.xCoord >> 4, tileEntity.zCoord >> 4);

					if (chunk != null) {
						chunk.cleanChunkBlockTileEntity(tileEntity.xCoord & 15, tileEntity.yCoord, tileEntity.zCoord & 15);
					}
				}
			}
			end = System.nanoTime();
			if (isGlobal || (x >> 4 == chunkX && z >> 4 == chunkZ)) {
				record(tileEntity, end - start);
			}
		}
	}

	public void record(Object o, long time) {
		if (time < 0) {
			time = 0;
		}
		getSingleTime(o).addAndGet(time);
		getSingleInvocationCount(o).incrementAndGet();
		Class<?> clazz = o.getClass();
		getTime(clazz).addAndGet(time);
		getInvocationCount(clazz).incrementAndGet();
		totalTime.addAndGet(time);
	}

	public void clear() {
		invocationCount.clear();
		time.clear();
		totalTime.set(0);
		singleTime.clear();
		singleInvocationCount.clear();
		ticks = 0;
	}

	public void tick() {
		if (profilingState != ProfileCommand.ProfilingState.NONE) {
			ticks++;
		}
	}

	public TableFormatter writeData(TableFormatter tf) {
		long timeProfiled = System.currentTimeMillis() - startTime;
		float tps = ticks * 1000f / timeProfiled;
		tf.sb.append("TPS: ").append(tps).append('\n').append(tf.tableSeparator);
		Map<Class<?>, Long> time = new HashMap<Class<?>, Long>();
		for (Map.Entry<Class<?>, AtomicLong> entry : this.time.entrySet()) {
			time.put(entry.getKey(), entry.getValue().get());
		}
		Map<Object, Long> singleTime = new HashMap<Object, Long>();
		for (Map.Entry<Object, AtomicLong> entry : this.singleTime.entrySet()) {
			singleTime.put(entry.getKey(), entry.getValue().get());
		}
		double totalTime = this.totalTime.get();
		final List<Object> sortedSingleKeysByTime = Ordering.natural().reverse().onResultOf(Functions.forMap(singleTime)).immutableSortedCopy(singleTime.keySet());
		tf
				.heading("Obj")
				.heading("Time/Tick")
				.heading("%");
		for (int i = 0; i < 5 && i < sortedSingleKeysByTime.size(); i++) {
			tf
					.row(niceName(sortedSingleKeysByTime.get(i)))
					.row(singleTime.get(sortedSingleKeysByTime.get(i)) / (1000000d * singleInvocationCount.get(sortedSingleKeysByTime.get(i)).get()))
					.row((singleTime.get(sortedSingleKeysByTime.get(i)) / totalTime) * 100);
		}
		tf.finishTable();
		tf.sb.append('\n');
		final Map<ChunkCoords, ComparableLongHolder> chunkTimeMap = new HashMap<ChunkCoords, ComparableLongHolder>() {
			@Override
			public ComparableLongHolder get(Object key_) {
				ChunkCoords key = (ChunkCoords) key_;
				ComparableLongHolder value = super.get(key);
				if (value == null) {
					value = new ComparableLongHolder();
					put(key, value);
				}
				return value;
			}
		};
		for (Object o : sortedSingleKeysByTime) {
			int x = Integer.MIN_VALUE;
			int z = Integer.MIN_VALUE;
			if (o instanceof Entity) {
				x = ((Entity) o).chunkCoordX;
				z = ((Entity) o).chunkCoordZ;
			} else if (o instanceof TileEntity) {
				x = ((TileEntity) o).xCoord >> 4;
				z = ((TileEntity) o).zCoord >> 4;
			}
			if (x != Integer.MIN_VALUE) {
				chunkTimeMap.get(new ChunkCoords(x, z)).value += singleTime.get(o);
			}
		}
		final List<ChunkCoords> sortedChunkCoordsByTime = Ordering.natural().reverse().onResultOf(Functions.forMap(chunkTimeMap)).immutableSortedCopy(chunkTimeMap.keySet());
		tf
				.heading("Chunk")
				.heading("Time/Tick")
				.heading("%");
		for (int i = 0; i < 5 && i < sortedChunkCoordsByTime.size(); i++) {
			ChunkCoords chunkCoordIntPair = sortedChunkCoordsByTime.get(i);
			long chunkTime = chunkTimeMap.get(chunkCoordIntPair).value;
			tf
					.row(chunkCoordIntPair.chunkXPos + ", " + chunkCoordIntPair.chunkZPos)
					.row(chunkTime / (1000000d * ticks))
					.row((chunkTime / totalTime) * 100);
		}
		tf.finishTable();
		tf.sb.append('\n');
		final List<Class<?>> sortedKeysByTime = Ordering.natural().reverse().onResultOf(Functions.forMap(time)).immutableSortedCopy(time.keySet());
		tf
				.heading("Class")
				.heading("Total Time/Tick")
				.heading("%");
		for (int i = 0; i < 5 && i < sortedKeysByTime.size(); i++) {
			tf
					.row(niceName(sortedKeysByTime.get(i)))
					.row(time.get(sortedKeysByTime.get(i)) / (1000000d * ticks))
					.row((time.get(sortedKeysByTime.get(i)) / totalTime) * 100);
		}
		tf.finishTable();
		tf.sb.append('\n');
		Map<Class<?>, Long> timePerTick = new HashMap<Class<?>, Long>();
		for (Map.Entry<Class<?>, AtomicLong> entry : this.time.entrySet()) {
			timePerTick.put(entry.getKey(), entry.getValue().get() / invocationCount.get(entry.getKey()).get());
		}
		final List<Class<?>> sortedKeysByTimePerTick = Ordering.natural().reverse().onResultOf(Functions.forMap(timePerTick)).immutableSortedCopy(timePerTick.keySet());
		tf
				.heading("Class")
				.heading("Time/tick")
				.heading("Calls");
		for (int i = 0; i < 5 && i < sortedKeysByTimePerTick.size(); i++) {
			tf
					.row(niceName(sortedKeysByTimePerTick.get(i)))
					.row(timePerTick.get(sortedKeysByTimePerTick.get(i)) / 1000000d)
					.row(invocationCount.get(sortedKeysByTimePerTick.get(i)));
		}
		tf.finishTable();
		return tf;
	}

	private static Object niceName(Object o) {
		if (o instanceof TileEntity) {
			return niceName(o.getClass()) + ' ' + ((TileEntity) o).xCoord + ',' + ((TileEntity) o).yCoord + ',' + ((TileEntity) o).zCoord;
		} else if (o instanceof Entity) {
			return niceName(o.getClass()) + ' ' + (int) ((Entity) o).posX + ',' + (int) ((Entity) o).posY + ',' + (int) ((Entity) o).posZ;
		}
		return o.toString().substring(0, 48);
	}

	private static String niceName(Class<?> clazz) {
		String name = MappingUtil.debobfuscate(clazz.getName());
		if (name.contains(".")) {
			String cName = name.substring(name.lastIndexOf('.') + 1);
			String pName = name.substring(0, name.lastIndexOf('.'));
			if (pName.contains(".")) {
				pName = pName.substring(pName.lastIndexOf('.') + 1);
			}
			return (cName.length() < 15 ? pName + '.' : "") + cName;
		}
		return name;
	}

	private final Map<Class<?>, AtomicInteger> invocationCount = new NonBlockingHashMap<Class<?>, AtomicInteger>();
	private final Map<Class<?>, AtomicLong> time = new NonBlockingHashMap<Class<?>, AtomicLong>();
	private final Map<Object, AtomicLong> singleTime = new NonBlockingHashMap<Object, AtomicLong>();
	private final Map<Object, AtomicLong> singleInvocationCount = new NonBlockingHashMap<Object, AtomicLong>();

	private AtomicLong getSingleInvocationCount(Object o) {
		AtomicLong t = singleInvocationCount.get(o);
		if (t == null) {
			synchronized (o) {
				t = singleInvocationCount.get(o);
				if (t == null) {
					t = new AtomicLong();
					singleInvocationCount.put(o, t);
				}
			}
		}
		return t;
	}

	// We synchronize on the class name as it is always the same object
	// We do not synchronize on the class object as that would also
	// prevent any synchronized static methods on it from running
	private AtomicInteger getInvocationCount(Class<?> clazz) {
		AtomicInteger i = invocationCount.get(clazz);
		if (i == null) {
			synchronized (clazz.getName()) {
				i = invocationCount.get(clazz);
				if (i == null) {
					i = new AtomicInteger();
					invocationCount.put(clazz, i);
				}
			}
		}
		return i;
	}

	private AtomicLong getSingleTime(Object o) {
		AtomicLong t = singleTime.get(o);
		if (t == null) {
			synchronized (o) {
				t = singleTime.get(o);
				if (t == null) {
					t = new AtomicLong();
					singleTime.put(o, t);
				}
			}
		}
		return t;
	}

	private AtomicLong getTime(Class<?> clazz) {
		AtomicLong t = time.get(clazz);
		if (t == null) {
			synchronized (clazz.getName()) {
				t = time.get(clazz);
				if (t == null) {
					t = new AtomicLong();
					time.put(clazz, t);
				}
			}
		}
		return t;
	}

	private class ComparableLongHolder implements Comparable<ComparableLongHolder> {
		public long value;

		ComparableLongHolder() {
		}

		@Override
		public int compareTo(final ComparableLongHolder comparableLongHolder) {
			long otherValue = comparableLongHolder.value;
			return (value < otherValue) ? -1 : ((value == otherValue) ? 0 : 1);
		}
	}

	private static final class ChunkCoords {
		public final int chunkXPos;
		public final int chunkZPos;

		ChunkCoords(final int chunkXPos, final int chunkZPos) {
			this.chunkXPos = chunkXPos;
			this.chunkZPos = chunkZPos;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof ChunkCoords && ((ChunkCoords) o).chunkXPos == this.chunkXPos && ((ChunkCoords) o).chunkZPos == this.chunkZPos;
		}

		@Override
		public int hashCode() {
			return (chunkXPos * 7907) + chunkXPos;
		}
	}
}
