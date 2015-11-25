package freenet.winterface.core;

import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableBiMap.Builder;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import freenet.client.FetchException;
import freenet.client.FetchException.FetchExceptionMode;
import freenet.client.async.PersistenceDisabledException;
import freenet.client.filter.ContentFilter;
import freenet.client.filter.FilterMIMEType;
import freenet.node.RequestStarter;
import freenet.clients.fcp.DownloadRequestStatus;
import freenet.clients.fcp.FCPServer;
import freenet.clients.fcp.RequestStatus;
import freenet.clients.fcp.UploadDirRequestStatus;
import freenet.clients.fcp.UploadFileRequestStatus;

/**
 * A util class which reads all the global requests from {@link FCPServer} (see
 * {@link FCPServer#getGlobalRequests()}) and divides them into separate lists
 * of {@link RequestStatus}.
 * <p>
 * It is possible to filter out the {@link RequestStatus} in final results. An
 * integer (called queue class) is used for that purpose. The constructor
 * accepts a combination of classes for filtering:
 * 
 * <pre>
 * new QueueUtil(DL_C_DISK | UP_F_DIR)}
 * </pre>
 * 
 * would return only download requests which have been completed (saved to disk)
 * and failed upload directories.
 * </p>
 * 
 * @author pausb
 */
public class QueueHelper {

	/** Contains total size of result */
	private int queueSize;
	/** Lowest found queue priority */
	private short lowestQueuedPriority;

	/** Contains desired target class */
	private final int requestedClass;
	/** {@link FCPServer} to read an manipulate messages */
	private final FCPServer fcp;

	/**
	 * {@link Map}s unknown MIME-Types to A list of {@link RequestStatus}*
	 * (immutable)
	 */
	public final ImmutableBiMap<String, List<RequestStatus>> dl_f_u_mime;
	/** Backing map of {@link #dl_f_u_mime} (mutable) */
	private final Map<String, List<RequestStatus>> dl_f_u_mimeBackingMap;
	/**
	 * {@link Map}s bad MIME-Types to A list of {@link RequestStatus}
	 * (immutable)
	 */
	public final ImmutableBiMap<String, List<RequestStatus>> dl_f_b_mime;
	/** Backing map of {@link #dl_f_b_mime} (mutable) */
	private final Map<String, List<RequestStatus>> dl_f_b_mimeBackingMap;

	/**
	 * A bi directional map of class codes to corresponding list of
	 * {@link RequestStatus} (immutable)
	 */
	public final ImmutableBiMap<Integer, List<RequestStatus>> requests;
	/** Backing map of {@link #requests} (mutable) */
	private final Map<Integer, List<RequestStatus>> requestsBackingMap;

	/** Total download queue size in byte */
	public final long totalQueueDownloadSize;
	/** Total upload queue size in byte */
	public final long totalQueueUploadSize;

	/** Meta flag to denote a download class (value is 1) */
	public final static int DL = 1;
	/** Flag to denote download to disk (value is 3) */
	public final static int DL_C_DISK = DL << 1 | DL;
	/** Flag to denote download to temp space (value is 5) */
	public final static int DL_C_TEMP = DL << 2 | DL;
	/** Flag to denote failed download (value is 9) */
	public final static int DL_F = DL << 3 | DL;
	/** Flag to denote uncompleted download (value is 17) */
	public final static int DL_UC = DL << 4 | DL;
	/** Flag to denote unknown MIME (value is 33) */
	public final static int DL_F_U_MIME = DL << 5 | DL;
	/** Flag to denote bad MIME (value is 65) */
	public final static int DL_F_B_MIME = DL << 6 | DL;
	/** Meta flag containing all download flags (value is 127) */
	public final static int DL_ALL = DL_C_DISK | DL_C_TEMP | DL_F | DL_UC | DL_F_U_MIME | DL_F_B_MIME;
	/** {@link List} of all download flags (except meta flags) */
	public final static List<Integer> DOWNLOAD_CLASSES = ImmutableList.of(DL_C_DISK, DL_C_TEMP, DL_F, DL_UC, DL_F_U_MIME, DL_F_B_MIME);

	/** Meta flag to denote upload classes (value is 128) */
	public final static int UP = DL << 7;
	/** Flag to denote completed uploads (value is 384) */
	public final static int UP_C = UP << 1 | UP;
	/** Flag to denote completed directory uploads (value is 640) */
	public final static int UP_C_DIR = UP << 2 | UP;
	/** Flag to denote failed uploads (value is 1152) */
	public final static int UP_F = UP << 3 | UP;
	/** Flag to denote failed upload directory (value is 2176) */
	public final static int UP_F_DIR = UP << 4 | UP;
	/** Flag to denote uncompleted uploads (value is 4224) */
	public final static int UP_UC = UP << 5 | UP;
	/** Flag to denote uncompleted directory uploads (value is 8320) */
	public final static int UP_UC_DIR = UP << 6 | UP;
	/** Meta flag containing all upload classes */
	public final static int UP_ALL = UP_C | UP_C_DIR | UP_F | UP_F_DIR | UP_UC | UP_UC_DIR;
	/** {@link List} of all upload flags (except meta flags) */
	public final static List<Integer> UPLOAD_CLASSES = ImmutableList.of(UP_C, UP_C_DIR, UP_F, UP_F_DIR, UP_UC, UP_UC_DIR);

	/** Maps classes (int) to corresponding {@link String}s */
	public final static BiMap<Integer, String> codeNameMap;

	/** Log4j logger */
	private final static Logger logger = Logger.getLogger(QueueHelper.class);

	static {
		Builder<Integer, String> builder = ImmutableBiMap.<Integer, String> builder();
		builder.put(DL_C_DISK, "completedDownloadToDisk");
		builder.put(DL_C_TEMP, "completedDownloadToTemp");
		builder.put(DL_F, "failedDownload");
		builder.put(DL_UC, "uncompletedDownload");
		builder.put(DL_F_U_MIME, "failedUnknownMIMEType");
		builder.put(DL_F_B_MIME, "failedBadMIMEType");
		builder.put(UP_C, "completedUpload");
		builder.put(UP_C_DIR, "completedDirUpload");
		builder.put(UP_F, "failedUpload");
		builder.put(UP_UC, "uncompletedUpload");
		builder.put(UP_UC_DIR, "uncompletedDirUpload");
		codeNameMap = builder.build();
	}

	/**
	 * Constructs.
	 * <p>
	 * Depending on given requested class, the {@link QueueHelper} filters all
	 * existing requests. For example if you want to get only a queue of
	 * finished downloads to disk use {@link #DL_C_DISK} or combine the classes
	 * to get multiple queues. For example {@code #DL_ALL | #UP_F} would give
	 * you all downloads and failed uploads.
	 * </p>
	 * 
	 * @param requestedClass
	 *            class of requested queues
	 * @param fcpServer
	 *            used to query global requests.
	 * @throws PersistenceDisabledException
	 *            is thrown if persistence is disabled
	 */
	public QueueHelper(int requestedClass, FCPServer fcpServer) throws PersistenceDisabledException {
		requestsBackingMap = Maps.newHashMap();
		dl_f_b_mimeBackingMap = Maps.newHashMap();
		dl_f_u_mimeBackingMap = Maps.newHashMap();
		queueSize = 0;
		logger.debug("Getting request queue for code " + Integer.toBinaryString(requestedClass));
		this.requestedClass = requestedClass;
		this.lowestQueuedPriority = RequestStarter.PAUSED_PRIORITY_CLASS;
		fcp = fcpServer;
		RequestStatus[] globalRequests;
		globalRequests = fcp.getGlobalRequests();
		long tmpTotalQueuedDownloadSize = 0;
		long tmpTotalQueuedUploadSize = 0;
		for (RequestStatus req : globalRequests) {
			// If current request (req) is a download request and user wanted to
			// get download requests (DL flag)
			tmpTotalQueuedDownloadSize += checkIfDownloadDesired(req);
			// If current request (req) is an upload request and user wanted to
			// get upload requests (UP flag)
			tmpTotalQueuedUploadSize += checkIfUploadDesired(req);
			// If current request (req) is an upload dir request and user wanted
			// to get upload requests (DL flag)
			tmpTotalQueuedUploadSize += checkIfUploadDirDesired(req);

		}
		totalQueueDownloadSize = tmpTotalQueuedDownloadSize;
		totalQueueUploadSize = tmpTotalQueuedUploadSize;
		// Create immutable bimaps from backing maps
		requests = ImmutableBiMap.copyOf(requestsBackingMap);
		dl_f_b_mime = ImmutableBiMap.copyOf(dl_f_b_mimeBackingMap);
		dl_f_u_mime = ImmutableBiMap.copyOf(dl_f_u_mimeBackingMap);
	}

	/**
	 * Checks if the given {@link RequestStatus} is an
	 * {@link DownloadRequestStatus} and if it matches the request class
	 * provided at the initialization (see {@link #requestedClass}). If yes, it
	 * adds the {@link RequestStatus} to the corresponding {@link List}
	 * 
	 * @param req
	 *            {@link RequestStatus} to check
	 * @return size of {@link RequestStatus} if its known and if
	 *         {@link RequestStatus} is desired, otherwise {@code 0}
	 * @see #get(int)
	 * @see #getList(int)
	 * @see #getMap(int)
	 */
	private long checkIfDownloadDesired(RequestStatus req) {
		long requestSize = 0;
		if (req instanceof DownloadRequestStatus && isDesired(DL)) {
			DownloadRequestStatus download = (DownloadRequestStatus) req;
			if (download.hasSucceeded()) {
				if (download.toTempSpace()) {
					addToList(download, DL_C_TEMP);
				} else {
					addToList(download, DL_C_TEMP);
				}
			} else if (download.hasFinished() && isDesired(DL_F)) {
				FetchExceptionMode failureCode = download.getFailureCode();
				if (failureCode == FetchExceptionMode.CONTENT_VALIDATION_UNKNOWN_MIME) {
					String mimeType = download.getMIMEType();
					mimeType = ContentFilter.stripMIMEType(mimeType);
					addToMap(download, mimeType, DL_F_U_MIME);
				} else if (failureCode == FetchExceptionMode.CONTENT_VALIDATION_BAD_MIME) {
					String mimeType = download.getMIMEType();
					mimeType = ContentFilter.stripMIMEType(mimeType);
					FilterMIMEType type = ContentFilter.getMIMEType(mimeType);
					if (type == null) {
						if (isDesired(DL_F_U_MIME)) {
							logger.warn("Bad MIME failure code yet MIME is " + mimeType + " which does not have a handler!");
							addToMap(download, mimeType, DL_F_U_MIME);
						}
					} else {
						addToMap(download, mimeType, DL_F_B_MIME);
					}
				} else {
					addToList(download, DL_F);
				}
			} else {
				// Request is still running
				short prio = download.getPriority();
				if (prio < lowestQueuedPriority) {
					lowestQueuedPriority = prio;
				}
				addToList(download, DL_UC);
				long size = download.getDataSize();
				if (size > 0) {
					requestSize = size;
				}
			}
		}
		return requestSize;
	}

	/**
	 * Checks if the given {@link RequestStatus} is an
	 * {@link UploadFileRequestStatus} and if it matches the request class
	 * provided at the initialization (see {@link #requestedClass}). If yes, it
	 * adds the {@link RequestStatus} to the corresponding {@link List}
	 * 
	 * @param req
	 *            {@link RequestStatus} to check
	 * @return size of {@link RequestStatus} if its known and if
	 *         {@link RequestStatus} is desired, otherwise {@code 0}
	 * @see #get(int)
	 * @see #getList(int)
	 */
	private long checkIfUploadDesired(RequestStatus req) {
		long requestSize = 0;
		if (req instanceof UploadFileRequestStatus && isDesired(UP)) {
			UploadFileRequestStatus upload = (UploadFileRequestStatus) req;
			if (upload.hasSucceeded()) {
				addToList(upload, UP_C);
			} else if (upload.hasFinished()) {
				addToList(upload, UP_F);
			} else {
				short prio = upload.getPriority();
				if (prio < lowestQueuedPriority) {
					lowestQueuedPriority = prio;
				}
				addToList(upload, UP_UC);
			}
			long size = upload.getDataSize();
			if (size > 0) {
				requestSize = size;
			}
		}
		return requestSize;
	}

	/**
	 * Checks if the given {@link RequestStatus} is an
	 * {@link UploadDirRequestStatus} and if it matches the request class
	 * provided at the initialization (see {@link #requestedClass}). If yes, it
	 * adds the {@link RequestStatus} to the corresponding {@link List}
	 * 
	 * @param req
	 *            {@link RequestStatus} to check
	 * @return size of {@link RequestStatus} if its known and if
	 *         {@link RequestStatus} is desired, otherwise {@code 0}
	 * @see #get(int)
	 * @see #getList(int)
	 */
	private long checkIfUploadDirDesired(RequestStatus req) {
		long requestSize = 0;
		if (req instanceof UploadDirRequestStatus && isDesired(UP)) {
			UploadDirRequestStatus upload = (UploadDirRequestStatus) req;
			if (upload.hasSucceeded()) {
				addToList(upload, UP_C_DIR);
			} else if (upload.hasFinished()) {
				addToList(upload, UP_F_DIR);
			} else {
				short prio = upload.getPriority();
				if (prio < lowestQueuedPriority) {
					lowestQueuedPriority = prio;
				}
				addToList(upload, UP_UC_DIR);
			}
			long size = upload.getTotalDataSize();
			if (size > 0) {
				requestSize = size;
			}
		}
		return requestSize;
	}

	/**
	 * @param targetClass
	 *            to check against requested class provided by initialization
	 * @return {@code true} if given target matches the initial requested class
	 * @see #matches(int, int)
	 * @see #requestedClass
	 */
	private boolean isDesired(int targetClass) {
		return matches(requestedClass, targetClass);
	}

	/**
	 * Throws {@link IllegalArgumentException} if given target class is made up
	 * of multiple classes
	 * 
	 * @param targetClass
	 *            an integer representing a queue class
	 */
	private static void classMustBeSingle(int targetClass) {
		int ones = countOnes(targetClass);
		if (ones != 2) {
			throw new IllegalArgumentException("Complex target classes cannot be accepted. Only one list at a time. You seem to query " + (ones - 1)
					+ " lists.");
		}
	}

	/**
	 * Fast way to count ones in binary representation of given number
	 * 
	 * @param i
	 *            integer to count ones
	 * @return number of ones
	 * @see <a
	 *      href="http://graphics.stanford.edu/~seander/bithacks.html#CountBitsSetParallel">reference</a>
	 */
	private static int countOnes(int i) {
		i = i - ((i >> 1) & 0x55555555);
		i = (i & 0x33333333) + ((i >> 2) & 0x33333333);
		return (((i + (i >> 4)) & 0x0F0F0F0F) * 0x01010101) >> 24;
	}

	/**
	 * Adds given {@link RequestStatus} to the {@link List} with desired target
	 * class
	 * 
	 * @param request
	 *            to add
	 * @param targetClass
	 *            queue class of {@link RequestStatus}
	 */
	private void addToList(RequestStatus request, int targetClass) {
		if (!isDesired(targetClass)) {
			return;
		}
		List<RequestStatus> list = requestsBackingMap.get(targetClass);
		if (list == null) {
			list = Lists.newLinkedList();
		}
		list.add(request);
		requestsBackingMap.put(targetClass, list);
		queueSize++;
		logger.trace("Added request " + request.hashCode() + " to list with code " + Integer.toBinaryString(targetClass));
	}

	/**
	 * Add given {@link RequestStatus} to the Map of unknown/bad MIME types
	 * regarding given target class and MIME type
	 * 
	 * @param request
	 *            to add
	 * @param MIMEType
	 *            content type
	 * @param targetClass
	 *            queue class of {@link RequestStatus}
	 */
	private void addToMap(RequestStatus request, String MIMEType, int targetClass) {
		if (!isDesired(targetClass)) {
			return;
		}
		List<RequestStatus> list = null;
		switch (targetClass) {
		case DL_F_B_MIME:
			list = dl_f_b_mimeBackingMap.get(MIMEType);
			if (list == null) {
				list = Lists.newLinkedList();
			}
			dl_f_b_mimeBackingMap.put(MIMEType, list);
			break;
		case DL_F_U_MIME:
			list = dl_f_u_mimeBackingMap.get(MIMEType);
			if (list == null) {
				list = Lists.newLinkedList();
			}
			dl_f_u_mimeBackingMap.put(MIMEType, list);
			break;
		default:
			logger.warn("Tried to add a RequestStatus with an unknown class: " + targetClass);
		}
		if (list != null) {
			list.add(request);
		}
		queueSize++;
	}

	/**
	 * Returns {@code true} if base class contains the target class.
	 * <p>
	 * The class {@link #DL_ALL} ({@code 1111111} in binary) matches
	 * {@link #DL_C_TEMP} ({@code 0000101} in binary), since
	 * 
	 * <pre>
	 * {@link #DL_ALL} & {@link #DL_C_TEMP} = {@link #DL_C_TEMP}
	 * </pre>
	 * 
	 * </p>
	 * 
	 * @param base
	 *            base class
	 * @param target
	 *            target class
	 * @return {@code false} if base class doesnt contain the target class
	 */
	public static boolean matches(int base, int target) {
		return ((base & target) == target);
	}

	/**
	 * Returns a {@link List} of {@link RequestStatus} corresponding to given
	 * queue class.
	 * <p>
	 * Queue class can be one of the following values:
	 * <ul>
	 * <li>{@link #DL_C_DISK}</li>
	 * <li>{@link #DL_C_TEMP}</li>
	 * <li>{@link #DL_F}</li>
	 * <li>{@link #DL_UC}</li>
	 * <li>{@link #UP_C}</li>
	 * <li>{@link #UP_C_DIR}</li>
	 * <li>{@link #UP_F}</li>
	 * <li>{@link #UP_F_DIR}</li>
	 * <li>{@link #UP_UC}</li>
	 * <li>{@link #UP_UC_DIR}</li>
	 * </ul>
	 * To access queues corresponding to {@link #DL_F_B_MIME} and
	 * {@link #DL_F_U_MIME} use {@link #getMap(int)}.
	 * </p>
	 * 
	 * @param targetClass
	 *            desired queue class
	 * @return desired {@link List}
	 */
	public List<RequestStatus> getList(int targetClass) {
		classMustBeSingle(targetClass);
		List<RequestStatus> result = requests.get(targetClass);
		return result != null ? ImmutableList.copyOf(result) : null;
	}

	/**
	 * Returns {@link Map} corresponding to desired target class.
	 * <p>
	 * {@link QueueHelper} generates maps for failed downloads caused by unknown
	 * ({@link #DL_F_U_MIME} flag) and bad ({@link #DL_F_B_MIME} flag) content
	 * types.
	 * </p>
	 * 
	 * @param targetClass
	 *            queue class of desired map. Must be either
	 *            {@link #DL_F_B_MIME} or {@link #DL_F_U_MIME}
	 * @return desired map
	 * @see #get(int)
	 * @see #getList(int)
	 */
	public Map<String, List<RequestStatus>> getMap(int targetClass) {
		if (targetClass == DL_F_B_MIME) {
			return dl_f_b_mime;
		} else if (targetClass == DL_F_U_MIME) {
			return dl_f_u_mime;
		} else {
			throw new IllegalArgumentException("Only applicable for values " + DL_F_B_MIME + " and " + DL_F_U_MIME);
		}
	}

	/**
	 * Returns a {@link List} of {@link RequestStatus} corresponding to desired
	 * queue class. This method is a combination of {@link #getList(int)} and
	 * {@link #getMap(int)}.
	 * <p>
	 * Since the queue classes of {@link #DL_F_B_MIME} and {@link #DL_F_U_MIME}
	 * correspond to {@link Map}s. This method just merges all values of those
	 * maps into a single {@link List} and returns that.
	 * </p>
	 * 
	 * @param targetClass
	 *            desired class
	 * @return desired list
	 * @see #getList(int)
	 * @see #getMap(int)
	 */
	public List<RequestStatus> get(int targetClass) {
		classMustBeSingle(targetClass);
		ImmutableCollection<List<RequestStatus>> values = null;
		if (targetClass == DL_F_U_MIME) {
			values = ImmutableList.copyOf(dl_f_b_mime.values());
		} else if (targetClass == DL_F_B_MIME) {
			values = ImmutableList.copyOf(dl_f_b_mime.values());
		}
		if (values != null) {
			List<RequestStatus> result = Lists.newLinkedList();
			for (List<RequestStatus> list : values) {
				result.addAll(list);
			}
			return ImmutableList.copyOf(result);
		} else {
			return getList(targetClass);
		}
	}

	/**
	 * This depends on the requested queue class by the initialization (
	 * {@link #requestedClass}). For example if {@link FCPServer} contains only
	 * {@code n} {@link DownloadRequestStatus} and the request class equals to
	 * {@link #UP_ALL}, this method would return zero since the
	 * {@link DownloadRequestStatus} were not desired.
	 * 
	 * @return total size of requested items
	 */
	public int getQueueSize() {
		return queueSize;
	}

	/**
	 * @return the lowest found queued priority from all <i>desired</i>
	 *         {@link RequestStatus}.
	 */
	public short getLowestQueuedPriority() {
		return lowestQueuedPriority;
	}

	/**
	 * @return {@link FCPServer} to fetch and manipulate global
	 *         {@link RequestStatus}s
	 */
	public FCPServer getFCPServer() {
		return fcp;
	}
}
