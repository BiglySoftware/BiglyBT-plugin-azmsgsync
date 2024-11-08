/*
 * Created on Oct 6, 2014
 * Created by Paul Gardner
 * 
 * Copyright 2014 Azureus Software, Inc.  All rights reserved.
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or 
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
 */


package com.vuze.plugins.azmsgsync;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.AlgorithmParameters;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.biglybt.core.CoreFactory;
import com.biglybt.core.config.COConfigurationManager;
import com.biglybt.core.util.AENetworkClassifier;
import com.biglybt.core.util.AERunStateHandler;
import com.biglybt.core.util.AERunnable;
import com.biglybt.core.util.AEThread2;
import com.biglybt.core.util.AEVerifier;
import com.biglybt.core.util.BDecoder;
import com.biglybt.core.util.BEncoder;
import com.biglybt.core.util.Base32;
import com.biglybt.core.util.ByteArrayHashMap;
import com.biglybt.core.util.ByteFormatter;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.FileUtil;
import com.biglybt.core.util.HashWrapper;
import com.biglybt.core.util.HashWrapper2;
import com.biglybt.core.util.RandomUtils;
import com.biglybt.core.util.SHA1Simple;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.ThreadPool;
import com.biglybt.core.util.TimeFormatter;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;
import com.biglybt.core.util.TimerEventPeriodic;
import com.biglybt.plugin.dht.impl.DHTPluginContactImpl;
import com.biglybt.util.MapUtils;
import com.biglybt.pif.ipc.IPCException;
import org.gudy.bouncycastle.crypto.engines.AESFastEngine;
import org.gudy.bouncycastle.crypto.modes.CBCBlockCipher;
import org.gudy.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.gudy.bouncycastle.crypto.params.KeyParameter;
import org.gudy.bouncycastle.crypto.params.ParametersWithIV;

import com.biglybt.core.dht.DHT;
import com.biglybt.core.dht.nat.DHTNATPuncher;
import com.biglybt.core.dht.transport.DHTTransportContact;
import com.biglybt.core.security.CryptoECCUtils;
import com.biglybt.core.security.CryptoManager;
import com.biglybt.core.security.CryptoSTSEngine;
import com.biglybt.core.util.CopyOnWriteList;
import com.biglybt.core.util.average.Average;
import com.biglybt.core.util.average.AverageFactory;
import com.biglybt.core.util.bloom.BloomFilter;
import com.biglybt.core.util.bloom.BloomFilterFactory;
import com.biglybt.plugin.dht.*;

public class 
MsgSyncHandler 
	implements DHTPluginTransferHandler, DHTPluginListener
{
		// version 1 - initial release
		// version 2 - invert deleted message bloom keys
		// version 3 - increased max msg size from 350 to 600
		// version 4 - optional reply compression
		// version 5 - optional control data
	
	private static final int VERSION		= 5;
	
	private static final int MIN_VERSION	= 4;
	
	private static final boolean TRACE = System.getProperty( "az.msgsync.trace.enable", "0" ).equals( "1" );
	
	private static final boolean TEST_LOOPBACK_CHAT = System.getProperty( "az.chat.loopback.enable", "0" ).equals( "1" );
	
	static{
		if ( TEST_LOOPBACK_CHAT ){
	
			Debug.outNoStack( "Loopback chat debug enabled, this BREAKS NON LOOPBACK CHAT!!!!");
		}
	}
	
	private static final String	HANDLER_BASE_KEY = "com.vuze.plugins.azmsgsync.MsgSyncHandler";
	private static final byte[]	HANDLER_BASE_KEY_BYTES;
	
	public static final int ST_INITIALISING		= 0;
	public static final int ST_RUNNING			= 1;
	public static final int ST_DESTROYED		= 2;
	
	static{
		byte[]	 bytes = null;
		
		try{
			bytes = new SHA1Simple().calculateHash( HANDLER_BASE_KEY.getBytes( "UTF-8" ));
			
		}catch( Throwable e ){
			
		}
		
		HANDLER_BASE_KEY_BYTES = bytes;
	}
	
	private static final byte[]	GENERAL_SECRET_RAND = {(byte)0x77,(byte)0x9a,(byte)0xb8,(byte)0xfd,(byte)0xe4,(byte)0x40,(byte)0x93,(byte)0x8d,(byte)0x58,(byte)0xad,(byte)0xf5,(byte)0xd4,(byte)0xa1,(byte)0x1b,(byte)0xe1,(byte)0xa8 };
	
	private static final int	RT_SYNC_REQUEST	= 0;
	private static final int	RT_SYNC_REPLY	= 1;
	private static final int	RT_DH_REQUEST	= 2;
	private static final int	RT_DH_REPLY		= 3;

	private int NODE_STATUS_CHECK_PERIOD			= 60*1000;
	private int	NODE_STATUS_CHECK_TICKS				= NODE_STATUS_CHECK_PERIOD / MsgSyncPlugin.TIMER_PERIOD;
	
	private int MSG_STATUS_CHECK_PERIOD				= 15*1000;
	private int	MSG_STATUS_CHECK_TICKS				= MSG_STATUS_CHECK_PERIOD / MsgSyncPlugin.TIMER_PERIOD;

	private int SECRET_TIDY_PERIOD					= 60*1000;
	private int	SECRET_TIDY_TICKS					= SECRET_TIDY_PERIOD / MsgSyncPlugin.TIMER_PERIOD;

	private int BIASED_BLOOM_CLEAR_PERIOD			= 60*1000;
	private int	BIASED_BLOOM_CLEAR_TICKS			= BIASED_BLOOM_CLEAR_PERIOD / MsgSyncPlugin.TIMER_PERIOD;

	private int CHECK_HISTORIES_PERIOD				= 60*1000;
	private int	CHECK_HISTORIES_TICKS				= CHECK_HISTORIES_PERIOD / MsgSyncPlugin.TIMER_PERIOD;

	
	private static final int STATUS_OK			= 1;
	private static final int STATUS_LOOPBACK	= 2;
	
	private static final Map<String,Object>		xfer_options = new HashMap<String, Object>();
	
	static{
		xfer_options.put( "disable_call_acks", true );
	}
	
	private static ThreadPool	sync_pool 	= new ThreadPool("MsgSyncHandler:pool", 32, true );

	private static final AtomicInteger				active_dht_checks		= new AtomicInteger();
	private static final int						MAX_ACTIVE_DHT_CHECKS	= 4;
	
	private final MsgSyncPlugin						plugin;
	private final DHTPluginInterface				dht;
	private final byte[]							user_key;
	private byte[]									dht_listen_key;
	private byte[]									dht_call_key;
	private volatile boolean						checking_dht;
	private volatile boolean						registering_dht;
	private long									last_dht_check;

	private final List<Object[]>					pending_handler_regs = new ArrayList<>();
	
	private byte[]									peek_xfer_key;
	private DHTPluginTransferHandler				peek_xfer_handler;
	
	private volatile boolean						dht_listen_keys_registered;
	
	private String						friendly_name = "";

	private final boolean				is_private_chat;
	private final boolean				is_anonymous_chat;
	
	private PrivateKey			private_key;
	private PublicKey			public_key;
	
	private byte[]				my_uid;
	private MsgSyncNode			my_node;
	
	private ByteArrayHashMap<List<MsgSyncNode>>		node_uid_map 		= new ByteArrayHashMap<List<MsgSyncNode>>();
	private ByteArrayHashMap<MsgSyncNode>			node_uid_loopbacks	= new ByteArrayHashMap<MsgSyncNode>();

	private static final int				MIN_BLOOM_BITS	= 8*8;
	
	private static final int				MAX_MESSAGES			= 128;
	private static final int				MAX_DELETED_MESSAGES	= 128;
	
	private static final int				MAX_NODES				= 128;
	private static final int				MIN_NODES				= 3;

	protected static final int				MAX_MESSAGE_SIZE		= 600;		// increased from 350 at version 3
	
	private static final int				MAX_MESSSAGE_REPLY_SIZE	= 4*1024;
	

	/*
	private static final int					ANON_DEST_USE_MIN_TIME	= 4500;
	
	private static WeakHashMap<String, Long>	anon_dest_use_map = new WeakHashMap<String, Long>();
	*/
		
	private Object							message_lock					= new Object();
	private LinkedList<MsgSyncMessage>		messages 						= new LinkedList<MsgSyncMessage>();
	private Map<HashWrapper,String>			deleted_messages_inverted_sigs_map = 
			new LinkedHashMap<HashWrapper,String>(MAX_DELETED_MESSAGES,0.75f,true)
			{
				@Override
				protected boolean
				removeEldestEntry(
			   		Map.Entry<HashWrapper,String> eldest) 
				{
					return size() > MAX_DELETED_MESSAGES;
				}
			};
			
	private int								message_mutation_id		= 0;	// needs to be zero as tested for
	private int								message_new_count;
	
	private ByteArrayHashMap<String>		message_sigs			= new ByteArrayHashMap<String>();
	
	private static final int MAX_HISTORY_RECORD_LEN	= 80;
	
	private Map<HashWrapper,String>	request_id_history = 
		new LinkedHashMap<HashWrapper,String>(512,0.75f,true)
		{
			@Override
			protected boolean
			removeEldestEntry(
		   		Map.Entry<HashWrapper,String> eldest) 
			{
				return size() > 512;
			}
		};
			
	private static final int MAX_CONC_SYNC	= 5;
	private static final int MAX_FAIL_SYNC	= 2;
		
	private static final int MAX_CONC_TUNNELS	= 3;
	
	private volatile long			first_sync_attempt_time		= -1;
	private volatile long			last_successful_sync_time 	= -1;
	
	private final long create_time;
	
	private Set<MsgSyncNode> active_syncs		 	= new HashSet<MsgSyncNode>();
	private Set<MsgSyncNode> active_tunnels	 		= new HashSet<MsgSyncNode>();
		
	private boolean	prefer_live_sync_outstanding;

		
	private CopyOnWriteList<MsgSyncListener>		listeners = new CopyOnWriteList<MsgSyncListener>();
	
	private volatile boolean		destroyed;
	
	private volatile int 		status			= ST_INITIALISING;
	private volatile int		last_dht_count	= -1;
	
	private long	dht_put_done_time = -1;
	
	private volatile int		in_req;
	private volatile int		out_req_ok;
	private volatile int		out_req_fail;
	
	private int	last_in_req;
	private int last_out_req;
	
	private Average		in_req_average 	= AverageFactory.MovingImmediateAverage( 30*1000/MsgSyncPlugin.TIMER_PERIOD );
	private Average		out_req_average = AverageFactory.MovingImmediateAverage( 30*1000/MsgSyncPlugin.TIMER_PERIOD );
	
	private volatile long message_sent_count;
	
	private long	last_not_delivered_reported;
		
	private volatile int		consec_no_more_to_come;
	private volatile int		last_more_to_come;
	
	private final byte[]	general_secret = new byte[16];
	
	private byte[]		managing_pk;
	private boolean		managing_ro;
	
		// for private message handler:
	
	private final MsgSyncHandler			parent_handler;
	private final byte[]					private_messaging_pk;
	private final Map<String,Object>		private_messaging_contact;
	private final MsgSyncNode				private_messaging_node;
	
	private volatile byte[]					private_messaging_secret;
	private boolean							private_messaging_secret_getting;
	private long							private_messaging_secret_getting_last;
	private boolean							private_messaging_fatal_error;
	
	private final Map<HashWrapper,Object[]>		secret_activities 		= new HashMap<HashWrapper,Object[]>();
	private final BloomFilter					secret_activities_bloom = BloomFilterFactory.createAddRemove4Bit( 1024 );
	
	private final BloomFilter			biased_node_bloom = BloomFilterFactory.createAddOnly( 512 );
	private volatile MsgSyncNode		biased_node_in;
	private volatile MsgSyncNode		biased_node_out;
	
	private volatile MsgSyncNode		random_liveish_node;
	
	private final int 	LIVE_NODE_BLOOM_TIDY_PERIOD			= 60*1000;
	private final int	LIVE_NODE_BLOOM_TIDY_TICKS			= LIVE_NODE_BLOOM_TIDY_PERIOD / MsgSyncPlugin.TIMER_PERIOD;

	private final int	SAVE_MESSAGES_PERIOD				= 60*1000;
	private final int 	SAVE_MESSAGES_TICKS					= SAVE_MESSAGES_PERIOD / MsgSyncPlugin.TIMER_PERIOD;;
	
	private long			live_node_counter_bloom_start 	= SystemTime.getMonotonousTime();
	private long			live_node_counter_last_new		= 0;
	
	private final BloomFilter	live_node_counter_bloom = BloomFilterFactory.createAddOnly( 1000 );
	private int					live_node_estimate;
	
	private boolean			save_messages;
	private int				save_messages_mutation_id	= message_mutation_id;
	private boolean			messages_loading;
	
	
	private boolean		node_banning_enabled	= true;
	
	private BloomFilter	history_key_bloom;
	private long		history_key_bloom_create_time;
	private int			history_key_bloom_size	= 1024;
	
	private Map<HashWrapper,HistoryWatchEntry>	history_watch_map 	= new HashMap<HashWrapper,HistoryWatchEntry>();
	private Set<HashWrapper>					history_bad_keys 	= new HashSet<HashWrapper>();

	private Map<HashWrapper2, SpammerEntry>	spammer_map			= new HashMap<HashWrapper2, SpammerEntry>();
	private Set<HashWrapper2>				spammer_bad_keys	= new HashSet<HashWrapper2>();
	
	private AtomicLong	v4_count = new AtomicLong();
	private AtomicLong	v6_count = new AtomicLong();
	
	private boolean		full_init;
	
	protected
	MsgSyncHandler(
		MsgSyncPlugin			_plugin,
		DHTPluginInterface		_dht,
		byte[]					_key,
		Map<String,Object>		_options,
		MsgSyncPeekListener		_peek_listener )
		
		throws Exception
	{
		create_time = SystemTime.getMonotonousTime();

		plugin			= _plugin;
		dht				= _dht;
		user_key		= _key;
			
		is_private_chat		= false;
		is_anonymous_chat	= dht.getNetwork() != AENetworkClassifier.AT_PUBLIC;
		
		parent_handler					= null;
		private_messaging_pk			= null;
		private_messaging_contact		= null;
		private_messaging_node			= null;
		
		try{
			if ( _peek_listener != null ){
			
				init( false );
			
				peekDHT( _options, _peek_listener );
				
			}else{
				
				updateOptions( _options );
				
				init( true );
			}
			
		}catch( Throwable e ){
			
			destroy( true );
			
			if ( e instanceof Exception ){
				
				throw((Exception)e);
				
			}else{
				
				throw( new Exception( e ));
			}
		}
	}
	
	protected
	MsgSyncHandler(
		MsgSyncPlugin			_plugin,
		DHTPluginInterface		_dht,
		byte[]					_key,
		Map<String,Object>		_options )
		
		throws Exception
	{
		this( _plugin, _dht, _key, _options, null );
	}
	
	private void
	init(
		boolean		full )
	
		throws Exception
	{	
		full_init		= full;
		
		byte[] gs = GENERAL_SECRET_RAND.clone();
		
		for ( int i=0; i<user_key.length;i++ ){
			
			gs[i%GENERAL_SECRET_RAND.length] ^= user_key[i];
		}
		
		gs = new SHA1Simple().calculateHash( gs );
		
		System.arraycopy( gs, 0, general_secret, 0, general_secret.length );

		dht_listen_key = new SHA1Simple().calculateHash( user_key );
		
		for ( int i=0;i<dht_listen_key.length;i++){
			
			dht_listen_key[i] ^= HANDLER_BASE_KEY_BYTES[i];
		}
		
		dht_call_key = dht_listen_key;

		peek_xfer_key = dht_listen_key.clone();
		
		peek_xfer_key[1] ^= 0x01;

		
		if ( full ){
			
			boolean	config_updated = false;
	
			String	config_key = CryptoManager.CRYPTO_CONFIG_PREFIX + "msgsync." + dht.getNetwork() + "." + ByteFormatter.encodeString( user_key );
	
			Map map = COConfigurationManager.getMapParameter( config_key, new HashMap());
	
				// see if this is a related channel and shares keys
								
			try{
				String str_key = new String( user_key, "UTF-8" );
				
				int	pos = str_key.lastIndexOf( '[' );
				
				if ( pos != -1 && str_key.endsWith( "]" )){
					
					String	base_key	= str_key.substring( 0, pos );
					
					friendly_name = base_key.trim();
					
					String	args_str = str_key.substring( pos+1, str_key.length() - 1 );
					
					String[] args = args_str.split( "&" );
					
					byte[] 	pk_arg 	= null;
					boolean ro_arg	= false;
					
					for ( String arg_str: args ){
						
						String[] bits = arg_str.split( "=" );
						
						String lhs = bits[0];
						String rhs = bits[1];
						
						if ( lhs.equals( "pk" )){
							
							pk_arg = Base32.decode( rhs );
							
						}else if ( lhs.equals( "ro" )){
							
							ro_arg = rhs.equals( "1" );
						}
					}
					
					if ( pk_arg != null ){
						
						managing_pk	= pk_arg;
						managing_ro = ro_arg;
						
						String	related_config_key = CryptoManager.CRYPTO_CONFIG_PREFIX + "msgsync." + dht.getNetwork() + "." + ByteFormatter.encodeString( base_key.getBytes( "UTF-8" ));
		
						Map related_map = COConfigurationManager.getMapParameter( related_config_key, new HashMap());
		
						if ( related_map != null ){
							
							byte[]	related_pk = (byte[])related_map.get( "pub" );
							
								// this channel's public key matches our existing one
							
							if ( Arrays.equals( pk_arg, related_pk )){
							
								byte[] existing_pk = (byte[])map.get( "pub" );
								
								if ( existing_pk == null || !Arrays.equals( existing_pk, related_pk )){
							
										// inherit the keys
									
									map.put( "pub", related_pk );
									map.put( "pri", related_map.get( "pri" ));
									
									config_updated = true;
								}
							}
						}
					}
				}else{
					
					friendly_name = str_key;
				}
			}catch( Throwable e ){
			}
				
			if ( managing_ro ){
				
				node_banning_enabled	= false; 
			}
			
			log( "Created" );
			
			byte[] _my_uid = (byte[])map.get( "uid" );
			
			if ( _my_uid == null || _my_uid.length != 8 ){
			
				_my_uid = new byte[8];
			
				RandomUtils.nextSecureBytes( _my_uid );
				
				map.put( "uid", _my_uid );
				
				config_updated = true;
			}
					
			my_uid = _my_uid;
			
			byte[]	public_key_bytes 	= (byte[])map.get( "pub" );
			byte[]	private_key_bytes 	= (byte[])map.get( "pri" );
			 
			PrivateKey	_private_key 	= null;
			PublicKey	_public_key		= null;
			
			if ( public_key_bytes != null && private_key_bytes != null ){
			
				try{
					_public_key		= CryptoECCUtils.rawdataToPubkey( public_key_bytes );
					_private_key	= CryptoECCUtils.rawdataToPrivkey( private_key_bytes );
					
				}catch( Throwable e ){
					
					_public_key		= null;
					_private_key	= null;
				}
			}
			
			if ( _public_key == null || _private_key == null ){
				
				KeyPair ecc_keys = CryptoECCUtils.createKeys();
	
				_public_key	= ecc_keys.getPublic();
				_private_key	= ecc_keys.getPrivate();
				
				map.put( "pub", CryptoECCUtils.keyToRawdata( _public_key ));
				map.put( "pri", CryptoECCUtils.keyToRawdata( _private_key ));
				
				config_updated = true;
			}
			
			public_key	= _public_key;
			private_key	= _private_key;
						
			dht.addListener( this );
			
			my_node	= new MsgSyncNode( dht.getLocalAddresses(), my_uid, CryptoECCUtils.keyToRawdata( public_key ));
		
			if ( MapUtils.getMapBoolean( map, "v6hint", false )){
				
				if ( !my_node.setIPv6Hint( true )){
					
					map.remove( "v6hint" );
					
					config_updated = true;
				}
			}
			
			if ( config_updated ){
				
				COConfigurationManager.setParameter( config_key, map );
				
				COConfigurationManager.setDirty();
			}

			boolean dht_initialising = dht.isInitialising();
			
			if ( !is_private_chat ){
							
				peek_xfer_handler = 
					new DHTPluginTransferHandler()
					{
						@Override
						public String
						getName()
						{
							return( "Message Sync (Peek): " + getString());
						}
						
						@Override
						public byte[]
						handleRead(
							DHTPluginContact	originator,
							byte[]				request_bytes )
						{
							updateProtocolCounts( originator.getAddress());
							
							try{
								Map<String,Object> request = BDecoder.decode( generalMessageDecrypt( request_bytes ));
										
								byte[] rand = (byte[])request.get( "r" );
								byte[] key	= (byte[])request.get( "k" );
								
								if ( rand == null || !Arrays.equals( peek_xfer_key, key )){
									
									return( null );
								}
								
								Map<String,Object> reply = new HashMap<String, Object>();
																
								int[] node_counts = getNodeCounts( false );
								
								int	total 	= node_counts[0];
								int live	= node_counts[1];

								reply.put( "t", total );
								reply.put( "l", live );
								reply.put( "e", getLiveNodeEstimate());
								
								synchronized( message_lock ){
									
									int num_messages = messages.size();
									
									reply.put( "m", num_messages );
									
									if ( num_messages > 0 ){
										
										MsgSyncMessage last_message = messages.getLast();
																				
										reply.put( "s", last_message.getSignature() );
										reply.put( "p", last_message.getNode().getPublicKey());
									}
								}
								
								return( generalMessageEncrypt( BEncoder.encode( reply )));
								
							}catch( Throwable e ){
							}
							
							return( null );
						}
						
						@Override
						public byte[]
						handleWrite(
							DHTPluginContact	originator,
							byte[]				key,
							byte[]				value )
						{
							updateProtocolCounts( originator.getAddress());
							
							return( null );
						}
					};
					
				if ( dht_initialising ){
					
					pending_handler_regs.add( new Object[]{ peek_xfer_key, peek_xfer_handler, xfer_options });

				}else{
					
					dht.registerHandler( 
						peek_xfer_key, 
						peek_xfer_handler, 
						xfer_options );
				}
			}
					
			if ( dht_initialising ){
				
				pending_handler_regs.add( new Object[]{ dht_listen_key, this, xfer_options });

			}else{
				
				dht.registerHandler( dht_listen_key, this, xfer_options );

				dht_listen_keys_registered	= true;
			}
			
			loadMessages();
			
			checkDHT();
			
		}else{
			
			try{
				String str_key = new String( user_key, "UTF-8" );
				
				int	pos = str_key.lastIndexOf( '[' );
				
				if ( pos != -1 && str_key.endsWith( "]" )){
					
					String	base_key	= str_key.substring( 0, pos );
					
					friendly_name = base_key.trim();
					
				}else{
					
					friendly_name = str_key;
					
				}
			}catch( Throwable e ){	
			}
			
			log( "Created" );
		}
	}
	
	protected
	MsgSyncHandler(
		MsgSyncPlugin			_plugin,
		DHTPluginInterface		_dht,
		MsgSyncHandler			_parent_handler,
		byte[]					_target_pk,
		Map<String,Object>		_target_contact,
		byte[]					_user_key,
		byte[]					_shared_secret )
				
		throws Exception
	{
		create_time = SystemTime.getMonotonousTime();
		
		plugin			= _plugin;
		dht				= _dht;
		
		is_private_chat		= true;
		is_anonymous_chat	= dht.getNetwork() != AENetworkClassifier.AT_PUBLIC;

		parent_handler				= _parent_handler;
		private_messaging_pk		= _target_pk;
		private_messaging_contact	= _target_contact;
		private_messaging_secret	= _shared_secret;
		
		node_banning_enabled	= false;
		
		if ( _user_key == null ){
			
			user_key		= new byte[16];
	
			RandomUtils.nextSecureBytes( user_key );
			
		}else{
			
			user_key		= _user_key;
		}
			// inherit the identity of parent
		
		public_key	= parent_handler.public_key;
		private_key	= parent_handler.private_key;	

		my_uid = new byte[8];
		
		RandomUtils.nextSecureBytes( my_uid );

		dht.addListener( this );
		
		my_node	= new MsgSyncNode( dht.getLocalAddresses(), my_uid, CryptoECCUtils.keyToRawdata( public_key ));
		
		dht_listen_key = new SHA1Simple().calculateHash( user_key );
		
		for ( int i=0;i<dht_listen_key.length;i++){
			
			dht_listen_key[i] ^= HANDLER_BASE_KEY_BYTES[i];
		}
		
		DHTPluginContact contact = dht.importContact( private_messaging_contact );
		
		if ( contact == null ){
			
			throw( new Exception( "Contact import failed: " + private_messaging_contact ));
		}
		
		private_messaging_node = addNode( contact, new byte[0], private_messaging_pk );
				
		dht_call_key = dht_listen_key.clone();
		
			// for testing purposes we use asymmetric keys for call/listen so things 
			// work in a single instance due to shared call-rendezvouz
			// unfortunately this doesn't work otherwise as the assumption is that a 'call' request on xfer key X is replied to on the same key :(
		
		if ( TEST_LOOPBACK_CHAT ){
			
			if ( _user_key == null ){
							
				dht_listen_key[0] ^= 0x01;
				
			}else{
				
				dht_call_key[0] ^= 0x01;
			}
		}
		
		if ( dht.isInitialising()){
			
			pending_handler_regs.add( new Object[]{ dht_listen_key, this, xfer_options });
			
		}else{
		
			dht.registerHandler( dht_listen_key, this, xfer_options );
		
			dht_listen_keys_registered = true;
		}
	}
		
	protected
	MsgSyncHandler(
		MsgSyncPlugin			_plugin,
		DHTPluginInterface		_dht,
		Map<String,Object>		_data )
		
		throws Exception
	{
		create_time = SystemTime.getMonotonousTime();
		
		plugin			= _plugin;
		dht				= _dht;
		user_key		= importB32Bytes( _data, "key" );
			
		is_private_chat		= false;
		is_anonymous_chat	= dht.getNetwork() != AENetworkClassifier.AT_PUBLIC;
		
		parent_handler					= null;
		private_messaging_pk			= null;
		private_messaging_contact		= null;
		private_messaging_node			= null;
		
		String	config_key = CryptoManager.CRYPTO_CONFIG_PREFIX + "msgsync." + dht.getNetwork() + "." + ByteFormatter.encodeString( user_key );

		Map<String,Object>	config_map = new HashMap<String, Object>();
		
		config_map.put( "uid", importB32Bytes( _data, "uid" ));
		config_map.put( "pub", importB32Bytes( _data, "pub" ));
		config_map.put( "pri", importB32Bytes( _data, "pri" ));
		
		COConfigurationManager.setParameter( config_key, config_map );
		
		COConfigurationManager.setDirty();
		
		init( true );
	}
	
	protected String
	getConfigKey()
	{
		return( CryptoManager.CRYPTO_CONFIG_PREFIX + "msgsync." + dht.getNetwork() + "." + ByteFormatter.encodeString( user_key ));
	}
	
	public void
	localAddressChanged(
		DHTPluginContact	local_contact )
	{
		my_node.updateContacts( dht.getLocalAddresses());
	}
	
	protected void
	updateOptions(
		Map<String,Object>		options )
	{
		Boolean b = (Boolean)options.get( "save_messages" );
		
		if ( b != null ){
			
			if ( save_messages != b ){
			
				save_messages = b;
			
				if ( !save_messages ){
				
					deleteMessages();
				}
			}
		}
		
		byte[]	pk 		= (byte[])options.get( "pk" );

		if ( pk != null ){
			
			Boolean spammer = (Boolean)options.get( "spammer" );

			if ( spammer != null ){
				
				synchronized( message_lock ){

					HashWrapper2 hw = new HashWrapper2( pk );
					
					SpammerEntry entry = spammer?spammer_map.get( hw ):spammer_map.remove( hw );
					
					if ( spammer ){
						
						if ( entry == null ){
							
							entry = new SpammerEntry( pk );
							
							spammer_map.put( hw, entry );
							
							for ( MsgSyncMessage msg: messages ){
								
								if ( Arrays.equals( pk, msg.getNode().getPublicKey())){
									
									byte[] history = msg.getHistory();
																		
									entry.addRecord( history );
								}
							}
						}
					}else{
						
						if ( entry != null ){
							
							entry.destroy();
						}
					}
				}
			}
		}
	}
	
	private static String
	importString(
		Map		map,
		String	key )
		
		throws Exception
	{
		Object obj = map.get( key );
		
		String	str;
		
		if ( obj instanceof String ){
		
			str = (String)obj;
			
		}else if ( obj instanceof byte[] ){
	
			str = new String((byte[])obj, "UTF-8" );
			
		}else{
			
			str = null;
		}
		
		return( str );
	}
	
	private static byte[]
	importB32Bytes(
		Map		map,
		String	key )
		
		throws Exception
	{
		String str = importString( map, key );
		
		return( Base32.decode( str ));
	}
	
	private void
	exportB32Bytes(
		Map			map,
		String		key,
		byte[]		bytes )
	{
		map.put( key, Base32.encode( bytes ));
	}
	
	public static Object[]
	extractKeyAndNetwork(
		Map<String,Object>		map )
		
		throws Exception
	{
		return( new Object[]{ importB32Bytes( map, "key" ), AENetworkClassifier.internalise( importString( map, "network" ))});
	}
		
	public Map<String,Object>
	export()
	{
		Map<String,Object>	result = new HashMap<String, Object>();
		
		exportB32Bytes( result, "key", user_key );
		
		result.put( "network", dht.getNetwork());
		
		exportB32Bytes( result, "uid", my_uid );
		
		try{
			exportB32Bytes( result, "pub", CryptoECCUtils.keyToRawdata( public_key ));
			exportB32Bytes( result, "pri", CryptoECCUtils.keyToRawdata( private_key ));
		
		}catch( Throwable e){
			
			Debug.out(e );
		}
		
		return( result );
	}
	
	protected MsgSyncPlugin
	getPlugin()
	{
		return( plugin );
	}
	
	@Override
	public String
	getName()
	{
		return( "Message Sync: " + getString());
	}

	public int
	getStatus()
	{
		return( status );
	}
	
	public byte[]
	getNodeID()
	{
		return( my_uid );
	}
	
	public byte[]
	getPublicKey()
	{
		try{
			return(CryptoECCUtils.keyToRawdata( public_key ));
			
		}catch( Throwable e ){
			
			Debug.out( e );
			
			return( null );
		}
	}
	
	public byte[]
	getManagingPublicKey()
	{
		return( managing_pk );
	}
	
	public boolean
	isReadOnly()
	{
		return( managing_ro );
	}
	
	public int
	getDHTCount()
	{
		return( last_dht_count );
	}
	
	private int[]	msg_count_cache;
	private long	msg_count_cache_time;

	public int[]
	getMessageCounts()
	{
		synchronized( message_lock ){
			
			long now = SystemTime.getMonotonousTime();
						
			if ( msg_count_cache != null && now - msg_count_cache_time < 10*1000 ){
					
				return( msg_count_cache );
			}
			
			int	msg_count 	= messages.size();
			int	out_pending	= getUndeliveredMessageCount();
			
			int in_pending;
			
			if ( consec_no_more_to_come >= 3 ){
				
				in_pending = 0;
				
			}else{
				
				in_pending = last_more_to_come;
				
				if ( in_pending == 0 ){
					
					if ( first_sync_attempt_time == -1 ){
					
						in_pending = -1;	// not started the sync process yet
						
					}else{
						
						if ( last_successful_sync_time == -1 ){
							
								// never got a sync reply, wait for a couple mins
							
							if ( now - first_sync_attempt_time < 2*60*1000 ){
								
								in_pending = -1;
							}
						}else{
							
								// synced at least once but now things have gone quiet
							
							if ( now - last_successful_sync_time < 2*60*1000 ){

								in_pending = -1;
							}
						}
					}
				}
			}
			
			msg_count_cache = new int[]{ msg_count, out_pending, in_pending };
			
			msg_count_cache_time	= now;
			
			return( msg_count_cache );
		}
	}
	
	private int[]	node_count_cache;
	private long	node_count_cache_time;
	
	public int[]
	getNodeCounts(
		boolean	allow_cache )
	{
		int	total	= 0;
		int	live	= 0;
		int	dying	= 0;
		
		long now = SystemTime.getMonotonousTime();
		
		synchronized( node_uid_map ){
		
			if ( allow_cache && node_count_cache != null && now - node_count_cache_time < 5000 ){
				
				return( node_count_cache );
			}
			
			for ( List<MsgSyncNode> nodes: node_uid_map.values()){
				
				for ( MsgSyncNode node: nodes ){
					
					total++;
					
					if ( node.getFailCount() == 0 ){
						
						if ( node.getLastAlive() > 0 ){
							
							live++;
						}
					}else{
						
						dying++;
					}
				}
			}
			
			node_count_cache = new int[]{ total, live, dying }; 
		
			node_count_cache_time	= now;
					
			return( node_count_cache );
		}
	}
	
	public double[]
	getRequestCounts()
	{
		return( 
			new double[]{ 
				in_req, 
				in_req_average.getAverage()*1000/MsgSyncPlugin.TIMER_PERIOD, 
				out_req_ok, 
				out_req_fail, 
				out_req_average.getAverage()*1000/MsgSyncPlugin.TIMER_PERIOD });
	}
	
	public List<MsgSyncMessage>
	getMessages()
	{
		synchronized( message_lock ){

			List<MsgSyncMessage> result = new ArrayList<MsgSyncMessage>( messages.size());

			for ( MsgSyncMessage msg: messages ){
				
				if ( msg.getMessageType() == MsgSyncMessage.ST_NORMAL_MESSAGE ){
					
					if ( !isControlMessage( msg )){
							
						result.add( msg );
					}
				}
			}
			
			return( result );
		}
	}
	
	protected DHTPluginInterface
	getDHT()
	{
		return( dht );
	}
	
	protected byte[]
	getUserKey()
	{
		return( user_key );
	}

	protected int
	getLiveNodeEstimate()
	{
		return( live_node_estimate );
	}
	
	private void
	nodeIsAlive(
		MsgSyncNode		node )
	{
		long now = SystemTime.getMonotonousTime();
		
		if ( live_node_counter_bloom.getEntryCount() >= 100 ){
			
				// too many to keep track beyond 100
			
			live_node_estimate = 100;
			
			live_node_counter_bloom.clear();
			
			live_node_counter_bloom_start = now;
		}
		
		byte[] key = node.getContactAddress().getBytes();
		
		boolean present = live_node_counter_bloom.contains( key );
		
		if ( present ){
			
			
				// not seen anything new recently, assume we've seen all there is
			
			if ( live_node_counter_last_new > 0 && now - live_node_counter_last_new > 2*60*1000 ){
				
				live_node_estimate = live_node_counter_bloom.getEntryCount();
				
				live_node_counter_bloom.clear();
				
				live_node_counter_bloom_start = now;
				
				live_node_counter_bloom.add( key );
				
				live_node_counter_last_new		= now;
	
			}
		}else{
			
				// something new, update our current estimate if needed
			
			live_node_counter_bloom.add( key );
			
			live_node_counter_last_new = now;
			
			int hits = live_node_counter_bloom.getEntryCount();
			
			if ( hits > live_node_estimate ){
				
				live_node_estimate = hits;
			}
		}
	}
	
	private void
	checkLiveNodeBloom()
	{		
		long now = SystemTime.getMonotonousTime();

		if ( 	( live_node_counter_last_new > 0 && now - live_node_counter_last_new >= 2*60*1000 ) ||
				( now - live_node_counter_bloom_start >= 10*60*1000 )){
			
			int	entries = live_node_counter_bloom.getEntryCount();

			live_node_estimate = entries;
			
			live_node_counter_bloom.clear();
			
			live_node_counter_bloom_start = now;
		}
	}
		
	private void
	peekDHT(
		Map<String,Object>				options,
		final MsgSyncPeekListener		peek_listener )
	{	
		if ( dht.isInitialising()){
			
			log( "DHT Initialising, peek skipped" );
			
			peek_listener.complete( this );
			
			return;
		}

		log( "Peeking DHT for nodes" );
		
		final long start 	= SystemTime.getMonotonousTime();
		
		Number	n_timeout = (Number)options.get( "timeout" );
		
		final long timeout = n_timeout==null?60*1000:n_timeout.longValue();
				
		dht.get(
			dht_listen_key,
			"Message Sync peek: " + getString(),
			DHTPluginInterface.FLAG_SINGLE_VALUE,
			32,
			timeout,
			false,
			true,
			new DHTPluginOperationAdapter() 
			{
				private int			active_threads = 0;
				
				private boolean		dht_done;
				private boolean		overall_done;
				
				private LinkedList<DHTPluginContact>	waiting_contacts = new LinkedList<DHTPluginContact>();
				
				@Override
				public boolean
				diversified() 
				{
					return( true );
				}
				
				@Override
				public void 
				valueRead(
					DHTPluginContact 	originator, 
					DHTPluginValue 		value ) 
				{
					updateProtocolCounts( originator.getAddress());
					
					synchronized( waiting_contacts ){
						
						if ( checkDone( false )){
							
							return;
						}
						
						waiting_contacts.add( originator );
						
						if ( active_threads < 5 ){
														
							active_threads++;
							
							new AEThread2( "msp:peek" )
							{
								@Override
								public void
								run()
								{
									while( true ){
										
										DHTPluginContact contact;
										
										synchronized( waiting_contacts ){
											
												// temporarily decrease active count for checkDone test
											
											active_threads--;

											if ( checkDone( false )){
																								
												return;
											}
											
											if ( waiting_contacts.isEmpty()){
																								
												break;
											}
											
											active_threads++;
											
											contact = waiting_contacts.removeFirst();
										}
										
										try{
											Map<String, Object> request = new HashMap<String, Object>();
											
											byte[] rand = new byte[16];
											
											RandomUtils.nextBytes( rand );
											
											request.put( "r", rand );
											
											request.put( "k", peek_xfer_key );
											
											byte[] bytes = generalMessageEncrypt( BEncoder.encode( request ));
											
											byte[] result = 
												contact.read(
													new DHTPluginProgressListener() {
														
														@Override
														public void reportSize(long size) {
														}
														
														@Override
														public void reportCompleteness(int percent) {
														}
														
														@Override
														public void reportActivity(String str) {
														}
													},
													peek_xfer_key,
													bytes,
													is_anonymous_chat?20*1000:10*1000 );
											
											if ( result != null ){
												
												Map<String,Object> reply = BDecoder.decode( generalMessageDecrypt( result ));
												
												try{
													if ( !peek_listener.dataReceived( MsgSyncHandler.this, reply )){
														
														checkDone( true );
													}
													
												}catch( Throwable e ){
													
													Debug.out( e );
												}
											}
																						
										}catch( Throwable e ){
										}
									}
								}
							}.start();
						}
					}
				}
				
				@Override
				public void 
				complete(
					byte[] 		key, 
					boolean 	timeout_occurred) 
				{	
					synchronized( waiting_contacts ){
						
						if ( dht_done ){
							
							return;
						}
						
						dht_done = true;
						
						if ( checkDone( false )){
							
							return;
						}
						
							// need to hang around to pick up results
						
						long	rem = timeout - ( SystemTime.getMonotonousTime() - start );
						
						if ( rem <= 0 ){
							
							checkDone( true );
							
						}else{
							
							SimpleTimer.addEvent(
								"msp:peek",
								SystemTime.getOffsetTime( rem ),
								new TimerEventPerformer()
								{									
									@Override
									public void 
									perform(
										TimerEvent event) 
									{
										checkDone( true );
									}
								});
						}
					}
				}
				
				private boolean
				checkDone(
					boolean	yes_we_are )
				{
					synchronized( waiting_contacts ){
						
						if ( overall_done ){
							
							return( true );
						}
						
						if ( 	destroyed || 
								yes_we_are ||
								( dht_done && active_threads == 0 ) ||
								SystemTime.getMonotonousTime() - start > timeout ){
							
							overall_done = true;
							
							waiting_contacts.clear();
							
							try{
								peek_listener.complete( MsgSyncHandler.this );
								
							}catch( Throwable e ){
								
								Debug.out( e );
							}
							
							return( true );
						}
					}
					
					return( false );
				}
			});		
	}
	
	private void
	checkDHT()
	{
		if ( parent_handler != null ){
			
			// no DHT activity for child handlers
		
			return;
		}	

		boolean went_async = false;
		
		synchronized( this ){
			
			if ( destroyed ){
				
				return;
			}
			
			if ( checking_dht ){
				
				return;
			}
			
			checking_dht = true;
			
			last_dht_check	= SystemTime.getMonotonousTime() + RandomUtils.nextLong( 20*1000 );	// randomise stuff a bit
		}
		
		try{
			if ( dht.isInitialising()){
				
				log( "DHT is initialising, skipping DHT node check" );
				
				return;
			}
			
			log( "Checking DHT for nodes" );
			
			dht.get(
				dht_listen_key,
				"Message Sync lookup: " + getString(),
				DHTPluginInterface.FLAG_SINGLE_VALUE,
				32,
				60*1000,
				false,
				true,
				new DHTPluginOperationAdapter() 
				{
					private boolean diversified;
						
					private int		dht_count = 0;
					
					@Override
					public boolean
					diversified() 
					{
						diversified = true;
						
						return( true );
					}
					
					@Override
					public void 
					valueRead(
						DHTPluginContact 	originator, 
						DHTPluginValue 		value ) 
					{
						updateProtocolCounts( originator.getAddress());
						
						try{
						
							Map<String,Object> m = BDecoder.decode( value.getValue());
						
							addDHTContact( originator, m );
							
							dht_count++;
							
						}catch( Throwable e ){
							
						}
					}
					
					@Override
					public void 
					complete(
						byte[] 		key, 
						boolean 	timeout_occurred) 
					{
						active_dht_checks.decrementAndGet();
						
						try{
							last_dht_count = dht_count;
							
							long now = SystemTime.getMonotonousTime();
							
							boolean	do_put;
							
							synchronized( MsgSyncHandler.this ){
							
								if ( 	dht_put_done_time == -1 || 
										( status == ST_INITIALISING && now - dht_put_done_time > 20*60*1000 )){
							
											// seen some chats stuck in 'initialising' after dht reconnect		
									
									dht_put_done_time = now;
									
									do_put = true;
									
								}else{
									
									do_put = false;
								}
							}
												
							if ( do_put ){
								
								if ( diversified ){
									
									log( "Not registering as sufficient nodes located" );
									
									status = ST_RUNNING;
									
								}else{
																		
									synchronized( MsgSyncHandler.this ){
										
										if ( registering_dht ){
											
											return;
										}
										
										registering_dht = true;
									}
									
									log( "Registering node" );

									Map<String,Object>	map = new HashMap<String,Object>();
									
									map.put( "u", my_uid );
									
									try{
										byte[] blah_bytes = BEncoder.encode( map );
										
										dht.put(
												dht_listen_key,
												"Message Sync write: " + getString(),
												blah_bytes,
												DHTPluginInterface.FLAG_SINGLE_VALUE,
												new DHTPluginOperationAdapter() {
																						
													@Override
													public boolean 
													diversified() 
													{
														return( false );
													}
													
													@Override
													public void 
													complete(
														byte[] 		key, 
														boolean 	timeout_occurred ) 
													{
														log( "Node registered" );
														
														synchronized( MsgSyncHandler.this ){
															
															registering_dht = false;
														}
														
														status = ST_RUNNING;
													}
												});
										
									}catch( Throwable e ){
										
										synchronized( MsgSyncHandler.this ){
											
											registering_dht = false;
										}
										
										Debug.out( e);
									}
								}
							}
						}finally{
							
							synchronized( MsgSyncHandler.this ){
								
								checking_dht = false;
							}
						}
					}
				});	
			
			active_dht_checks.incrementAndGet();
			
			went_async = true;
			
		}catch( Throwable e ){
			
			// can get here if dht not yet initialised
			
		}finally{
			
			if ( !went_async ){
				
				synchronized( this ){
					
					checking_dht = false;
				}
			}
		}				
	}
	
	protected boolean
	timerTick(
		int		count )
	{
		if ( destroyed ){
			
			return( false );
		}
		
		if ( !dht_listen_keys_registered ){
			
			synchronized( pending_handler_regs ){
								
				if ( !dht.isInitialising()){
					
					for ( Object[] entry: pending_handler_regs ){
						
						try{
							dht.registerHandler(
								(byte[])entry[0],
								(DHTPluginTransferHandler)entry[1],
								(Map<String,Object>)entry[2] );
								
						}catch( Throwable e ){
							
							Debug.out( e );
						}
					}
					
					pending_handler_regs.clear();
					
					dht_listen_keys_registered = true;
				}
			}
		}
		
		if ( count % SECRET_TIDY_TICKS == 0 ){

			synchronized( secret_activities ){
				
				secret_activities_bloom.clear();
				
				Iterator<Object[]>	it = secret_activities.values().iterator();
				
				long	now = SystemTime.getMonotonousTime();
						
				while( it.hasNext()){
					
					long	time = (Long)it.next()[0];
					
					if ( now - time > 60*1000 ){
						
						it.remove();
					}
				}
			}
		}
		
		if ( count % BIASED_BLOOM_CLEAR_TICKS == 0 ){

			synchronized( biased_node_bloom ){
				
				biased_node_bloom.clear();
			}
		}
			
		if ( count % CHECK_HISTORIES_TICKS == 0 ){

			checkHistories();
		}
		
		if ( parent_handler != null ){
			
			if ( private_messaging_secret == null ){
				
				if ( private_messaging_fatal_error ){
					
					return( false );
				}
				
				if ( parent_handler.destroyed ){
					
					reportErrorText( "azmsgsync.report.pchat.destroyed" );
					
					private_messaging_fatal_error = true;
					
					return( false );
				}
				
				synchronized(  MsgSyncHandler.this ){
					
					if ( !private_messaging_secret_getting ){
				
						long now = SystemTime.getMonotonousTime();
						
						if ( now - private_messaging_secret_getting_last < 20*1000 ){
							
							return( false );
						}
						
						private_messaging_secret_getting = true;
						
						private_messaging_secret_getting_last	= now;
						
						reportInfoText( "azmsgsync.report.connecting" );
						
						new AEThread2( "MsgSyncHandler:getsecret"){
							
							@Override
							public void run() {
								try{
									boolean[] fatal_error = { false };

									try{
										
										private_messaging_secret = parent_handler.getSharedSecret( private_messaging_node, private_messaging_pk, user_key, fatal_error );
										
										if ( private_messaging_secret != null ){
											
											reportInfoText( "azmsgsync.report.connected" );	
										}
									}catch( IPCException e ){
										
										if ( fatal_error[0] ){
											
											private_messaging_fatal_error = true;
										}
										
										reportErrorRaw( e.getMessage());
									}
								}finally{
									
									synchronized(  MsgSyncHandler.this ){
										
										private_messaging_secret_getting = false;
									}
								}
							}
						}.start();
					}
				}
			}
		}
		
		int	in_req_diff 	= in_req - last_in_req;
		int	out_req			= out_req_fail + out_req_ok;
		int out_req_diff	= out_req	- last_out_req;
		
		in_req_average.update( in_req_diff );
		out_req_average.update( out_req_diff );
		
		last_out_req	= out_req;
		last_in_req		= in_req;
		
		//trace( in_req_average.getAverage()*1000/MsgSyncPlugin.TIMER_PERIOD + "/" + out_req_average.getAverage()*1000/MsgSyncPlugin.TIMER_PERIOD);
		
		
		if ( count % MSG_STATUS_CHECK_TICKS == 0 ){
			
			if ( message_sent_count > 0 ){
				
				long now = SystemTime.getCurrentTime();
				
				synchronized( message_lock ){

					int	not_delivered_count = 0;
					
					boolean	have_old_ones	= false;
					
					for ( MsgSyncMessage msg: messages ){
						
						if ( msg.getNode() == my_node ){
							
								// delivery isn't sufficient as the reply might not get through :(
							
							int	delivery_count 		= msg.getDeliveryCount();
							
							boolean	not_seen 		= msg.getSeenCount() == 0 && msg.getProbablySeenCount() < 5;
							
							if ( delivery_count == 0 || not_seen ){
								
								long period = MSG_STATUS_CHECK_PERIOD*(delivery_count+1);
								
								if ( is_anonymous_chat ){
									
									period *= 2;
								}
								
								if ( now - msg.getTimestamp() > period ){
								
									have_old_ones = true;
								}
														
								not_delivered_count++;
							}
						}
					}
					
					if ( have_old_ones && last_not_delivered_reported != not_delivered_count ){
						
						last_not_delivered_reported = not_delivered_count;
						
						reportInfoText( "azmsgsync.report.not.delivered", String.valueOf( not_delivered_count ));
						
					}else{
						
						if ( last_not_delivered_reported > 0 && not_delivered_count == 0 ){
							
							last_not_delivered_reported = 0;
							
							reportInfoText( "azmsgsync.report.all.delivered" );
						}
					}
				}
			}
		}
		
		if ( count % NODE_STATUS_CHECK_TICKS == 0 ){
			
			int	failed	= 0;
			int	live	= 0;
			int	total	= 0;
			
			List<MsgSyncNode>	to_remove = new ArrayList<MsgSyncNode>();
			
			synchronized( node_uid_map ){
				
				List<MsgSyncNode>	living		 	= new ArrayList<MsgSyncNode>( MAX_NODES*2 );
				List<MsgSyncNode>	not_failing	 	= new ArrayList<MsgSyncNode>( MAX_NODES*2 );
				List<MsgSyncNode>	failing 		= new ArrayList<MsgSyncNode>( MAX_NODES*2 );
								
				//if ( TRACE )trace( "Current nodes: ");
				
				for ( List<MsgSyncNode> nodes: node_uid_map.values()){
					
					for ( MsgSyncNode node: nodes ){
						
						//if ( TRACE )trace( "    " + node.getContact().getAddress() + "/" + ByteFormatter.encodeString( node.getUID()));
						
						total++;
						
						if ( node.getFailCount() > 0 ){
							
							failed++;
							
							if ( node.getFailCount() > 1 ){
								
								to_remove.add( node );
								
							}else{
								
								failing.add( node );
							}
						}else{
							
							if ( node.getLastAlive() > 0 ){
												
								live++;
								
								living.add( node );
								
							}else{
								
								not_failing.add( node );
							}
						}
					}
				}
				
				int	excess = total - to_remove.size() - MAX_NODES;
				
				if ( excess > 0 ){
					
					List<List<MsgSyncNode>>	lists = new ArrayList<List<MsgSyncNode>>();
					
					Collections.shuffle( living );
					
					lists.add( failing );
					lists.add( not_failing );
					lists.add( living );
					
					for ( List<MsgSyncNode> list: lists ){
						
						if ( excess == 0 ){
							
							break;
						}
						
						for ( MsgSyncNode node: list ){
							
							to_remove.add( node );
							
							excess--;
							
							if ( excess == 0 ){
								
								break;
							}
						}
					}
				}else{
					
						// make sure we don't throw away too many nodes and end up with nothing
					
					int rem = total - to_remove.size();
					
					if ( rem < MIN_NODES ){
						
						int	retain = MIN_NODES - rem;
						
						for ( int i=0;i<retain;i++){
							
							if ( to_remove.size() == 0 ){
								
								break;
							}
							
							to_remove.remove( RandomUtils.nextInt( to_remove.size()));
						}
					}
				}
			}
			
			log( "Node status: live=" + live + ", failed=" + failed + ", total=" + total + ", to_remove=" + to_remove.size() + "; messages=" + messages.size());
			
			for ( MsgSyncNode node: to_remove ){
				
					// don't remove private chat node
				
				if ( node == private_messaging_node ){
					
					continue;
				}
				
				removeNode( node, false );
			}
			
			long	now = SystemTime.getMonotonousTime();
			
			long elapsed = now - last_dht_check;
			
			if ( 	( status == ST_INITIALISING && !( checking_dht || registering_dht )) ||
					live == 0 ||
					( live < 50  && elapsed > live*60*1000 )   ||
					( live < 100 && elapsed > live*2*60*1000 ) ||
					elapsed > live*4*60*1000 ){
				
				if ( active_dht_checks.get() <= MAX_ACTIVE_DHT_CHECKS ){
				
					checkDHT();
				}
			}
		}
		
		if ( count % LIVE_NODE_BLOOM_TIDY_TICKS == 0 ){

			checkLiveNodeBloom();
		}
		
		if ( count % SAVE_MESSAGES_TICKS == 0 ){

			saveMessages();
		}
		
		long	last_message_secs_ago;
		
		synchronized( message_lock ){
			
			if ( messages.isEmpty()){
				
				last_message_secs_ago = (SystemTime.getMonotonousTime() - create_time )/1000;
				
			}else{
				
				last_message_secs_ago = messages.getLast().getAgeSecs();
			}
		}
		
			// slower sync rate for anonymous, higher latency/cost 
		
		int SYNC_TICK_COUNT = is_anonymous_chat?2:1;
		
		if ( last_message_secs_ago < 2*60 ){
			
		}else if ( last_message_secs_ago < 5*60 ){
			
			SYNC_TICK_COUNT *= 2;
			
		}else if ( last_message_secs_ago < 60*60 ){

			SYNC_TICK_COUNT *= 3;
			
		}else if ( last_message_secs_ago < 5*60 ){
			
			SYNC_TICK_COUNT *= 4;
		}
		
		if ( AERunStateHandler.isDHTSleeping()){
			
			SYNC_TICK_COUNT *= 2;
		}
		
		//System.out.println( getName() + ": sync=" + SYNC_TICK_COUNT );
		
		if ( count % ( SYNC_TICK_COUNT)  == 0 ){

			return( sync());
		}
		
		return( false );
	}

	private boolean
	addDHTContact(
		DHTPluginContact		contact,
		Map<String,Object>		map )
	{				
		byte[] uid = (byte[])map.get( "u" );
				
		if ( uid != null ){
			
				// we have no verification as to validity of the contact/uid at this point - it'll get checked later
				// if/when we obtain its public key
			
			if ( addNode( contact, uid, null ) != my_node ){
			
				return( true );
			}
		}
		
		return( false );
	}
	
	private MsgSyncNode
	addNode(
		DHTPluginContact		contact,
		byte[]					uid,
		byte[]					public_key )
	{
		MsgSyncNode node = addNodeSupport( contact, uid, public_key );
		
		if ( public_key != null ){
			
			if ( !node.setDetails( contact, public_key)){
								
				node = new MsgSyncNode( contact, uid, public_key );
			}
		}
		
		return( node );
	}
	
	private MsgSyncNode
	addNodeSupport(
		DHTPluginContact		contact,
		byte[]					uid,
		byte[]					public_key )
	{
			// we need to always return a node as it is required to create associated messages and we have to create each message otherwise
			// we'll keep on getting it from other nodes
		
		if ( uid == my_uid ){
			
			return( my_node );
		}
		
		synchronized( node_uid_map ){
			
			MsgSyncNode loop = node_uid_loopbacks.get( uid );
				
			if ( loop != null ){
				
				return( loop );
			}
			
			List<MsgSyncNode> nodes = node_uid_map.get( uid );
						
			if ( nodes != null ){
				
				for ( MsgSyncNode n: nodes ){
					
					if ( sameContact( n.getContact(), contact )){
						
						return( n );
					}
				}
			}
							
			if ( nodes == null ){
				
				nodes = new ArrayList<MsgSyncNode>();
				
				node_uid_map.put( uid, nodes );
			}
			
			MsgSyncNode node = new MsgSyncNode( contact, uid, public_key );
				
			nodes.add( node );		

			if ( TRACE )trace( "Add node: " + contact.getName() + ByteFormatter.encodeString( uid ) + "/" + (public_key==null?"no PK":"with PK" ) + ", total uids=" + node_uid_map.size());
			
			return( node );
		}	
	}
	
	private void
	removeNode(
		MsgSyncNode		node,
		boolean			is_loopback )
	{
		synchronized( node_uid_map ){
			
			byte[]	node_id = node.getUID();
			
			if ( is_loopback ){
				
				node_uid_loopbacks.put( node_id, node );
			}
			
			List<MsgSyncNode> nodes = node_uid_map.get( node_id );
			
			if ( nodes != null ){
				
				if ( nodes.remove( node )){
					
					if ( nodes.size() == 0 ){
						
						node_uid_map.remove( node_id );
					}
					
					//if ( TRACE )trace( "Remove node: " + node.getContact().getName() + ByteFormatter.encodeString( node_id ) + ", loop=" + is_loopback );
				}
			}
		}
	}
	
	private List<MsgSyncNode>
	getNodes(
		byte[]		node_id )
	{
		synchronized( node_uid_map ){

			List<MsgSyncNode> nodes = node_uid_map.get( node_id );
			
			if ( nodes != null ){
				
				nodes = new ArrayList<MsgSyncNode>( nodes );
			}
			
			return( nodes );
		}
	}
	
	protected static String
	getString(
		DHTPluginContact		c )
	{
		InetSocketAddress a = c.getAddress();
		
		if ( a.isUnresolved()){
			
			return( a.getHostName() + ":" + a.getPort());
			
		}else{
			
			return( a.getAddress().getHostAddress() + ":" + a.getPort());
		}
	}
	
	private boolean
	sameContact(
		DHTPluginContact		c1,
		DHTPluginContact		c2 )
	{
		InetSocketAddress a1 = c1.getAddress();
		InetSocketAddress a2 = c2.getAddress();
		
		if ( a1.getPort() == a2.getPort()){
			
			if ( a1.isUnresolved() && a2.isUnresolved()){
				
				return( a1.getHostName().equals( a2.getHostName()));
				
			}else if ( a1.isUnresolved() || a2.isUnresolved()){
				
				return( false );
				
			}else{
				
				return( a1.getAddress().equals( a2.getAddress()));
			}
		}else{
			
			return( false );
		}
	}
	
	private void
	processManagementMessage(
		MsgSyncMessage		message )
	{
		
		//trace( "management message:" + message );
	}
	
	
	private boolean
	processControlMessage(
		MsgSyncMessage		message )
	{
		byte[] control = message.getControl();
		
		if ( control != null ){
			
			try{
				Map map = BDecoder.decode( control );
				
				Long type= (Long)map.get( "t" );
				
				if ( type == 0 ){
					
					byte[]		pk_bytes	= (byte[])map.get( "k");
					byte[]		sig_bytes	= (byte[])map.get( "s");
					
					String pk_str = Base32.encode( pk_bytes );
					
					AEVerifier.verifyData( pk_str, sig_bytes );
					
					if (((Long)map.get( "o" )).intValue() == 0 ){
					
						plugin.addGlobalBan( pk_bytes );
						
					}else{
						
						plugin.removeGlobalBan( pk_bytes );
					}
				}
			}catch( Throwable e ){
			
			}
		}
		
		return( control != null );
	}
	
	private boolean
	isControlMessage(
		MsgSyncMessage		message )
	{
		return( message.getControl() != null );
	}
	
	final static int	MS_LOCAL		= 0;
	final static int	MS_INCOMING		= 1;
	final static int	MS_LOADING		= 2;
	
	private boolean
	addMessage(
		MsgSyncNode				node,
		byte[]					message_id,
		byte[]					content,
		byte[]					control,
		byte[]					signature,
		int						age_secs,
		byte[]					history,
		Map<String,Object>		opt_contact,
		int						msg_source )
	{
		MsgSyncMessage msg = new MsgSyncMessage( node, message_id, content, control, signature, age_secs, history );

		return( addMessage( msg, opt_contact, msg_source ));
	}
	
	private boolean
	addMessage(
		MsgSyncMessage 			msg,
		Map<String,Object>		opt_contact,
		int						msg_source )
	{
		MsgSyncNode node = msg.getNode();
		
		byte[]	originator_pk = node.getPublicKey();
		
		boolean management_message = managing_pk != null && Arrays.equals( originator_pk, managing_pk );
		
		boolean is_incoming_or_loading = msg_source == MS_INCOMING || msg_source == MS_LOADING;
		
		if ( is_incoming_or_loading ){
		
				// reject all messages in read only channels that aren't from the owner
			
			if ( managing_ro ){
				
				if ( !management_message ){
					
					return( false );
				}				
			}
			
				// see if we have restarted and re-read our message from another node. If so
				// then mark it as delivered
			
			if ( Arrays.equals( originator_pk, my_node.getPublicKey())){
				
				msg.delivered();

				msg.seen();
			}
		}
		
		if ( management_message ){
			
			processManagementMessage( msg );
		}
		
			// used to do history update (and flood detection) here but moved to after processing so as to only
			// operate on messages that are considered new (as opposed to possible replays of old messages by nodes
			// coming online after being dead for a while)
		
		if ( is_incoming_or_loading && opt_contact != null ){
			
				// see if this is a more up-to-date contact address for the contact
			
			long last = node.getLatestMessageTimestamp();
			
			long current = msg.getTimestamp();
			
			if ( current > last ){
				
				DHTPluginContact new_contact = dht.importContact( opt_contact );
				
				if ( new_contact != null ){
				
					node.setDetails( new_contact, current );
				}
			}
		}
				
		if ( msg.getMessageType() == MsgSyncMessage.ST_NORMAL_MESSAGE || is_incoming_or_loading ){
			
				// remember message if is it valid or it is incoming - latter is to 
				// prevent an invalid incoming message from being replayed over and over
			
			byte[]	signature = msg.getSignature();
			
			synchronized( message_lock ){
			
				if ( message_sigs.containsKey( signature )){
										
					return( false );
				}
				
				byte[] inv_signature = signature.clone();
				
				for ( int i=0;i<inv_signature.length;i++ ){
					
					inv_signature[i] ^= 0xff;
				}
				
				if ( deleted_messages_inverted_sigs_map.containsKey( new HashWrapper( inv_signature ))){
										
					return( false );
				}
				
				message_sigs.put( signature, "" );
				
				int	num_messages = messages.size();
				
				ListIterator<MsgSyncMessage> lit = messages.listIterator( num_messages );
				
				int		insertion_point = num_messages;
				boolean	added			= false;
				
				int	age_secs = msg.getAgeSecsWhenReceived();
				
				while( lit.hasPrevious()){
						
					insertion_point--;

					MsgSyncMessage prev  = lit.previous();
					
					if ( prev.getAgeSecs() >= age_secs ){
						
						lit.next();
						
						lit.add( msg );
						
						added = true;
						
						break;
					}			
				}
				
				if ( !added ){
				
						// no older messages found, stick it at the front
					
					insertion_point = 0;
					
					messages.addFirst( msg );
				}
				
				if ( messages.size() > MAX_MESSAGES ){
											
					MsgSyncMessage removed = messages.removeFirst();
						
					byte[]	removed_sig = removed.getSignature();
					
					message_sigs.remove( removed_sig );
					
					if ( removed == msg ){
											
							// not added after all
						
						if ( deleted_messages_inverted_sigs_map.size() < MAX_DELETED_MESSAGES ){
							
								// prevent further replay
							
							deleted_messages_inverted_sigs_map.put( new HashWrapper( inv_signature ), "" );
							
							message_mutation_id++;
						}
						
						return( false );
					}
					
					byte[] removed_inv_sig = removed_sig.clone();
					
					for ( int j=0;j<removed_inv_sig.length;j++){
						
						removed_inv_sig[j] ^= 0xff; 
					}
					
					deleted_messages_inverted_sigs_map.put( new HashWrapper( removed_inv_sig ), "" );
				}
									
				message_mutation_id++;

				if ( msg_source != MS_LOADING ){
				
					if ( insertion_point > num_messages / 2 ){
						
							// only count as new if it ain't that old!
						
						message_new_count++;
					}
				}
			}
		}
		
			// message accepted, now check flooding
		
		byte[] history = msg.getHistory();
		
		if ( msg_source == MS_INCOMING && history.length > 0 ){
			
			if ( !historyReceived( originator_pk, history )){
				
				msg.setLocalMessage( "Message ignored due to spam/flooding" );
			}
		}
		
		if ( msg.getMessageType() == MsgSyncMessage.ST_NORMAL_MESSAGE || !is_incoming_or_loading ){
			
			if ( processControlMessage( msg )){
				
			}else{
	
					// we want to deliver any local error responses back to the caller but not
					// incoming messages that are errors as these are maintained for house
					// keeping purposes only
				
				for ( MsgSyncListener l: listeners ){
					
					try{
						l.messageReceived( msg );
						
					}catch( Throwable e ){
						
						Debug.out( e );
					}
				}
			}
		}
		
		return( true );
	}
		
	private void
	processCommand(
		String		cmd )
	{
		try{
			if ( cmd.equals( "reset" )){
				
				resetHistories();
				
				reportInfoRaw( "Reset performed" );
			
				return;
				
			}else if ( cmd.equals( "dump" )){
				
				System.out.println( "Nodes" );
				
				long now = SystemTime.getMonotonousTime();
				
				synchronized( node_uid_map ){
					
					int	total_alive = 0;
					
					for ( List<MsgSyncNode> nodes: node_uid_map.values()){
						
						for ( MsgSyncNode node: nodes ){
							
							int	fails = node.getFailCount();
							
							long last_alive = node.getLastAlive();
							
							String prefix;
							
							if ( fails == 0 && last_alive > 0 ){
								
								prefix = "   *";
								
								total_alive++;
								
							}else{
								
								prefix = "    ";
							}
							
							String	last_alive_str;
							
							if ( last_alive > 0 ){
								
								last_alive_str = ", alive ago=" + TimeFormatter.format(( now - last_alive )/1000 );
								
							}else{
								
								last_alive_str = "";
							}
							
							System.out.println( prefix + node.getContactAddress() + ", fails=" + fails + last_alive_str);
						}
					}
					
					System.out.println( "Total alive: " + total_alive );
				}
				
				System.out.println( "Messages" );
				
				synchronized( message_lock ){

					for ( int i=0;i<messages.size(); i++ ){
						
						MsgSyncMessage message = messages.get(i);
						
						byte[]	sig = message.getSignature();
												
						String msg_id = ByteFormatter.encodeString( sig, 8, 3 );
						
						System.out.println( "    " + msg_id + ", age=" +  message.getAgeSecs() + " (" + message.getAgeSecsWhenReceived() + ")" + ", delivered=" + message.getDeliveryCount() + ", seen=" + message.getSeenCount() + ", content=" + new String( message.getContent()) + ", control=" + message.getControl() + ", pk=" + Base32.encode( message.getNode().getPublicKey()));
					}
				}
				
				System.out.println( "History bloom: " + (history_key_bloom==null?"null":history_key_bloom.getString()));
				
				System.out.println( "global_bans: " + plugin.getGlobalBans());
				
				return;
				
			}else{
				
				String[] bits = cmd.split( "[\\s]+" );
				
				cmd = bits[0].toLowerCase( Locale.US );
				
				if ( cmd.equals( "unban" )){
					
					String	node = bits[1];
					
					if ( bits.length > 1 ){
						
						if ( node.equalsIgnoreCase( "all" )){
							
							resetHistories();
							
							reportInfoRaw( "All nodes unbanned" );
							
						}else{
							
							HashWrapper hkey = new HashWrapper( ByteFormatter.decodeString( bits[1] ));
							
							synchronized( message_lock ){
								
								if ( history_bad_keys.remove( hkey )){
									
									reportInfoRaw( "Node unbanned" );
									
								}else{
									
									reportErrorRaw( "Node not found" );
								}
							}
						}
						
						return;
					}
					
				}else if ( cmd.equals( "banning" )){
					
					if ( bits.length > 1 ){

						String	arg = bits[1];
						
						if ( arg.equalsIgnoreCase( "enable" )){
							
							resetHistories( true );
							
							reportInfoRaw( "Banning enabled" );
							
						}else if ( arg.equalsIgnoreCase( "disable" )){
							
							resetHistories( false );
							
							reportInfoRaw( "Banning disabled" );
							
						}else{
							
							reportErrorRaw( "invalid argument '" + arg + "'" );
						}
						
						return;
					}
				}else if ( cmd.equals( "status" )){
					
					reportHistoryStatus();
					
					reportSpamStatus();
					
					return;
					
				}else if ( cmd.equals( "global_ban" )){
					
					if ( bits.length != 3 ){
						
						reportInfoRaw( "Insufficient args: global_ban <pk> <sig>" );
						
					}else{
					
						String pk_str 	= bits[1];
						String sig_str	= bits[2];
						
						try{
							byte[] pk_bytes 	= Base32.decode( pk_str );
							byte[] sig_bytes 	= ByteFormatter.decodeString( sig_str );
							
							AEVerifier.verifyData( pk_str,sig_bytes );
							
							Map<String,Object> control = new HashMap<>();
							
							control.put( "t", 0L );
								
							control.put( "o", 0L );	// add
							
							control.put( "k", pk_bytes );
							control.put( "s", sig_bytes );
							
							sendMessageSupport( new byte[0], BEncoder.encode( control ));
							
							reportInfoRaw( "ban accepted" );
							
						}catch( Throwable e ){
							
							reportInfoRaw( "signature verification failure" );
						}
					}
					
					return;
				}
			}
						
			reportErrorRaw( "Unrecognized/Invalid control command: " + cmd );
			
		}catch( Throwable  e){
			
			reportErrorRaw( "Control command processing failed: " + Debug.getNestedExceptionMessage( e ));
		}
	}
	
	private static final int	LAST_MESSAGE_WINDOW = 60;
	private static final int	LAST_MESSAGE_LIMIT	= 30;		// 30 messages in 60 seconds
	
	private final int[]		last_message_times 		= new int[LAST_MESSAGE_LIMIT];
	private int				last_message_times_pos	= 0;
	
	private int				last_flood_warning		= 0;
	
	public void
	sendMessage(
		byte[]				content,
		byte[]				control,
		Map<String,Object>	options )
	{
		Boolean is_local 	= (Boolean)options.get( "is_local" );
		Boolean is_control 	= (Boolean)options.get( "is_control" );

		if ( is_local != null && is_local ){
			
			try{
				String message = (String)options.get( "message" );
				
				int	message_type = ((Number)options.get( "message_type" )).intValue();
				
				if ( message_type == 1 || message_type == 2 ){
				
					reportInfoRaw( message );
					
				}else{
					
					reportErrorRaw( message );
				}
			}catch( Throwable e ){
				
				Debug.out( e );
			}
			
		}else if ( is_control != null && is_control ){
			
			String cmd = (String)options.get( "cmd" );

			processCommand( cmd );
			
		}else{
				
			synchronized( last_message_times ){
			
				int now_secs = (int)( SystemTime.getMonotonousTime()/1000 );

				int	limit_secs = now_secs - LAST_MESSAGE_WINDOW;
				
				int	newest	= 0;
				int	oldest	= Integer.MAX_VALUE;
				
				int	count = 0;
				
				for ( int i=0;i<last_message_times.length;i++){
				
					int	time = last_message_times[i];
					
					if ( time > 0 ){
						
						if ( time >= limit_secs ){
							
							count++;
							
							if ( time > newest ){
								
								newest = time;
							}
							
							if ( time < oldest ){
								
								oldest = time;
							}
						}
					}
				}
				
				int	remaining_messages 	= LAST_MESSAGE_LIMIT - count;
				int remaining_secs		= oldest + LAST_MESSAGE_WINDOW - now_secs;
				
				int	delay_millis;
				
				if ( count < LAST_MESSAGE_LIMIT/4){
					
					delay_millis = 0;
					
				}else if ( count < LAST_MESSAGE_LIMIT/2 ){
					
					delay_millis = 1000;
										
				}else{
					
					if ( count > 3*LAST_MESSAGE_LIMIT/4 ){
						
						if ( !managing_ro ){
							
							if ( last_flood_warning == 0 || now_secs - last_flood_warning > 60 ){
								
								last_flood_warning = now_secs;
								
								reportErrorRaw( "You are flooding the channel. Excessive flooding will result in a PERMANENT ban." );
							}
						}
					}
					
					if ( remaining_secs <= 0 || remaining_messages <= 0 ){
						
						delay_millis = (LAST_MESSAGE_WINDOW*1000)/LAST_MESSAGE_LIMIT;
						
					}else{
					
						delay_millis = (remaining_secs * 1000)/ remaining_messages;
					}
				}
				
				//System.out.println( "delay=" + delay_millis + ", count=" + count + ", rem_secs=" + remaining_secs + ", rem_msg=" + remaining_messages);
				
				last_message_times[last_message_times_pos++%last_message_times.length] = now_secs;
				
				if ( delay_millis > 0 ){
					
					try{
						Thread.sleep( delay_millis );
						
					}catch( Throwable e ){
						
					}
				}
				
				message_sent_count++;
			}
						
			sendMessageSupport( content, control );
		}
	}
	
	private void
	reportInfoText(
		MsgSyncListener		listener,
		String				resource_key,
		String...			args )
	{
		reportSupport( listener, "i:" + plugin.getMessageText( resource_key, args ));
	}
	
	private void
	reportInfoText(
		String		resource_key,
		String...	args )
	{
		reportSupport( null, "i:" + plugin.getMessageText( resource_key, args ));
	}
	
	private void
	reportInfoRaw(
		String		info )
	{
		reportSupport( null, "i:" + info );
	}
	
	private void
	reportErrorText(
		String		resource_key,
		String...	args )
	{
		reportSupport( null, "e:" + plugin.getMessageText( resource_key, args ));
	}
	
	private void
	reportErrorRaw(
		String		error )
	{
		reportSupport( null, "e:" + error );
	}
	
	private void
	reportSupport(
		MsgSyncListener		opt_listener,
		String				str )
	{
		try{
			Signature sig = CryptoECCUtils.getSignature( private_key );

			byte[]	message_id = new byte[8];
			
			RandomUtils.nextSecureBytes( message_id );
			
			sig.update( my_uid );
			sig.update( message_id );
			
			byte[]	sig_bytes = sig.sign();
			
			MsgSyncMessage msg = new MsgSyncMessage( my_node, message_id, sig_bytes, str  );
			
			if ( opt_listener == null ){
				
				for ( MsgSyncListener l: listeners ){
					
					try{
						l.messageReceived( msg );
						
					}catch( Throwable e ){
						
						Debug.out( e );
					}
				}
			}else{
				
				opt_listener.messageReceived( msg );
			}
			
		}catch( Throwable e ){
			
			e.printStackTrace();
		}
	}
	
	private void
	sendMessageSupport(
		byte[]		content,
		byte[]		control )
	{
		if ( content == null ){
			
			content = new byte[0];
		}
		
		try{
			Signature sig = CryptoECCUtils.getSignature( private_key );
			
			byte[]	message_id = new byte[8];
			
			RandomUtils.nextSecureBytes( message_id );
			
			sig.update( my_uid );
			sig.update( message_id );
			sig.update( content );
			
			if ( control != null ){
				sig.update( control );
			}
			
			byte[]	sig_bytes = sig.sign();
			
			addMessage( my_node, message_id, content, control, sig_bytes, 0, null, null, MS_LOCAL );
			
			sync( true );
			
		}catch( Throwable e ){
			
			e.printStackTrace();
		}
	}
		
	private void
	tryTunnel(
		final MsgSyncNode		node,
		boolean					short_cache,
		final Runnable			to_run )
	{		
		if ( is_anonymous_chat ){
			
			return;
		}
		
		long	last_tunnel = node.getLastTunnel();
		
		if ( last_tunnel != 0 && SystemTime.getMonotonousTime() - last_tunnel < (short_cache?45*1000:2*60*1000 )){
			
			return;
		}
		
		synchronized( active_tunnels ){
			
			if ( active_tunnels.size() < MAX_CONC_TUNNELS && !active_tunnels.contains( node )){
				
				active_tunnels.add( node );
				
				node.setLastTunnel( SystemTime.getMonotonousTime());
				
				new AEThread2( "msgsync:tunnel"){
					
					@Override
					public void run(){
					
						boolean	worked = false;
						
						try{
							DHTPluginContact		rendezvous = node.getRendezvous();

							if ( TRACE )trace( "Tunneling to " + node.getName() + ", rendezvous=" + rendezvous );
							
							DHTPluginContact contact = node.getContact();
							
							if ( rendezvous != null && contact instanceof DHTPluginContactImpl){
								
								DHTPluginContactImpl impl = (DHTPluginContactImpl)contact;
								
								if ( impl.openTunnel( new DHTPluginContact[]{ rendezvous }, null ) != null ){
									
									if ( TRACE )trace( "    tunneling to " + node.getName() + " worked" );
							
									worked = true;
									
									if ( to_run != null ){
									
										to_run.run();
									}
								}
							}else{
								
								if ( contact.openTunnel() != null ){
									
									if ( TRACE )trace( "    tunneling to " + node.getName() + " worked" );
							
									worked = true;
									
									if ( to_run != null ){
									
										to_run.run();
									}
								}
							}
						}catch( Throwable e ){
							
						}finally{
							
							if ( !worked ){
								
								if ( TRACE )trace( "    tunneling to " + node.getName() + " failed");
							}
							
							synchronized( active_tunnels ){
								
								active_tunnels.remove( node );
							}
						}
					}
				}.start();
			}
		}
	}
	
	private byte[]
	getSharedSecret(
		MsgSyncNode		target_node,
		byte[]			target_pk,
		byte[]			user_key,
		boolean[]		fatal_error )
		
		throws IPCException
	{
		try{
				// no harm in throwing in a tunnel attempt regardless
						
			tryTunnel( target_node, true, null );
			
			CryptoSTSEngine sts = CoreFactory.getSingleton().getCryptoManager().getECCHandler().getSTSEngine( public_key, private_key );
			
			Map<String,Object>		request_map = new HashMap<String, Object>();
			
			byte[] act_id = new byte[8];
			
			RandomUtils.nextSecureBytes( act_id );
			
			request_map.put( "v", VERSION );
			
			request_map.put( "t", RT_DH_REQUEST );

			request_map.put( "i", act_id );
			
			request_map.put( "u", user_key );
			
			ByteBuffer buffer = ByteBuffer.allocate( 16*1024 );
			
			sts.getKeys( buffer );
			
			buffer.flip();
			
			byte[]	keys = new byte[ buffer.remaining()];
			
			buffer.get( keys );
			
			request_map.put( "k", keys );
			
			byte[]	request_data = BEncoder.encode( request_map );
				
			request_data = generalMessageEncrypt( request_data );
			
			byte[] reply_bytes = 
				target_node.getContact().call(
					new DHTPluginProgressListener() {
						
						@Override
						public void reportSize(long size) {
						}
						
						@Override
						public void reportCompleteness(int percent) {
						}
						
						@Override
						public void reportActivity(String str) {
						}
					},
					dht_call_key,
					request_data, 
					30*1000 );
			
			reply_bytes = generalMessageDecrypt( reply_bytes );
			
			Map<String,Object> reply_map = BDecoder.decode( reply_bytes );

			if ( reply_map.containsKey( "error" )){
				
				throw( new IPCException( new String((byte[])reply_map.get( "error" ), "UTF-8" )));
			}
			
			int	type = reply_map.containsKey( "t" )?((Number)reply_map.get( "t" )).intValue():-1; 

			if ( type == RT_DH_REPLY ){
				
				request_map.remove( "k" );
								
				byte[]	their_keys = (byte[])reply_map.get( "k" );
				
				if ( their_keys == null ){
					
					throw( new Exception( "keys missing" ));
				}

				sts.putKeys( ByteBuffer.wrap( their_keys ));
				
				buffer.position( 0 );
			
				sts.getAuth( buffer );
				
				buffer.flip();
				
				byte[]	my_auth = new byte[ buffer.remaining()];
				
				buffer.get( my_auth );
				
				request_map.put( "a", my_auth );
	
				byte[]	their_auth = (byte[])reply_map.get( "a" );
				
				if ( their_auth == null ){
					
					throw( new Exception( "auth missing" ));
				}

				sts.putAuth( ByteBuffer.wrap( their_auth ));
				
				byte[] shared_secret = fixSecret( sts.getSharedSecret());
				
				byte[] rem_pk = sts.getRemotePublicKey();
				
				boolean pk_ok =  Arrays.equals( target_pk, rem_pk );
								
				if ( !pk_ok ){
					
					fatal_error[0] = true;
					
					throw( new IPCException( "Public key mismatch" ));
				}
				
				request_data = BEncoder.encode( request_map );

				request_data = generalMessageEncrypt( request_data );

				reply_bytes = 
						target_node.getContact().call(
							new DHTPluginProgressListener() {
								
								@Override
								public void reportSize(long size) {
								}
								
								@Override
								public void reportCompleteness(int percent) {
								}
								
								@Override
								public void reportActivity(String str) {
								}
							},
							dht_call_key,
							request_data, 
							30*1000 );
				
				reply_bytes = generalMessageDecrypt( reply_bytes );

				reply_map = BDecoder.decode( reply_bytes );
				
				if ( reply_map.containsKey( "error" )){
					
					throw( new IPCException( new String((byte[])reply_map.get( "error" ), "UTF-8" )));
				}
				
				return( shared_secret );

			}
		}catch( IPCException e ){
			
			throw( e );
			
		}catch( Throwable e ){
			
		}
		
		return( null );
	}
	
	
	private byte[]
	generalMessageEncrypt(
		byte[]	data )
	{
		try{
			byte[] key = general_secret;
							
			SecretKeySpec secret = new SecretKeySpec( key, "AES");
		
			Cipher encipher = Cipher.getInstance("AES/CBC/PKCS5Padding" );
					
			encipher.init( Cipher.ENCRYPT_MODE, secret );
					
			AlgorithmParameters params = encipher.getParameters();
					
			byte[] IV = params.getParameterSpec(IvParameterSpec.class).getIV();
					
			byte[] enc = encipher.doFinal( data );
		
			byte[] rep_bytes = new byte[ IV.length + enc.length ];
				
			System.arraycopy( IV, 0, rep_bytes, 0, IV.length );
			System.arraycopy( enc, 0, rep_bytes, IV.length, enc.length );
			
			return( rep_bytes );
			
		}catch( Throwable e ){
			
			// Debug.out( e );
			
			return( null );
		}
	}
	
	private byte[]
	generalMessageDecrypt(
		byte[]	data )
	{
		try{
			if ( data.length % 16 != 0 ){
				
				return( null );
			}
			
			byte[] key = general_secret;
								
			SecretKeySpec secret = new SecretKeySpec( key, "AES");
	
			Cipher decipher = Cipher.getInstance ("AES/CBC/PKCS5Padding" );
				
			decipher.init(Cipher.DECRYPT_MODE, secret, new IvParameterSpec( data, 0, 16 ));
	
			byte[] result = decipher.doFinal( data, 16, data.length-16 );
			
			return( result );
			
		}catch( Throwable e ){
			
			// Debug.out( e );
			
			return( null );
		}
	}
	
	private static final int AES_BYTES = 24;
	
	private byte[]
	fixSecret(
		byte[]	secret )
	{
		int	len = secret.length;
		
		if ( len == AES_BYTES ){
			
			return( secret );
			
		}else{
			
			byte[]	result = new byte[AES_BYTES];
			
			if ( len < AES_BYTES ){
		
				System.arraycopy( secret, 0, result, 0, len );
			
			}else{
				
				System.arraycopy( secret, len-AES_BYTES, result, 0, AES_BYTES );
			}
			
			return( result );
		}		
	}
	
	private byte[]
	privateMessageEncrypt(
		byte[]	data )
	{
		try{
			byte[] key = private_messaging_secret;
			
			if ( AES_BYTES == 16 ){
				
				SecretKeySpec secret = new SecretKeySpec( key, "AES");
		
				Cipher encipher = Cipher.getInstance("AES/CBC/PKCS5Padding" );
					
				encipher.init( Cipher.ENCRYPT_MODE, secret );
					
				AlgorithmParameters params = encipher.getParameters();
					
				byte[] IV = params.getParameterSpec(IvParameterSpec.class).getIV();
					
				byte[] enc = encipher.doFinal( data );
		
				byte[] rep_bytes = new byte[ IV.length + enc.length ];
				
				System.arraycopy( IV, 0, rep_bytes, 0, IV.length );
				System.arraycopy( enc, 0, rep_bytes, IV.length, enc.length );
				
				return( rep_bytes );
				
			}else{
				
				AESFastEngine aes = new AESFastEngine();
				
				CBCBlockCipher cbc=new CBCBlockCipher(aes);
				
				PaddedBufferedBlockCipher s = new PaddedBufferedBlockCipher(cbc);
				
				byte[]  IV		= new byte[16];
				
				RandomUtils.nextSecureBytes( IV );
				
				s.init( true, new ParametersWithIV( new KeyParameter(key), IV ));
				
				byte[] enc = new byte[data.length+64];	// add some extra space for padding if needed
				
				int done = s.processBytes(data, 0, data.length, enc, 0);

				done += s.doFinal( enc, done);

				byte[] rep_bytes = new byte[ IV.length + done ];
				
				System.arraycopy( IV, 0, rep_bytes, 0, IV.length );
				System.arraycopy( enc, 0, rep_bytes, IV.length, done );
				
				return( rep_bytes );
			}	
		}catch( Throwable e ){
			
			Debug.out( e );
			
			return( null );
		}
	}
	
	private byte[]
	privateMessageDecrypt(
		byte[]	data )
	{
		try{
			byte[] key = private_messaging_secret;

			if ( AES_BYTES == 16 ){
								
				SecretKeySpec secret = new SecretKeySpec( key, "AES");
		
				Cipher decipher = Cipher.getInstance ("AES/CBC/PKCS5Padding" );
					
				decipher.init(Cipher.DECRYPT_MODE, secret, new IvParameterSpec( data, 0, AES_BYTES ));
		
				byte[] result = decipher.doFinal( data, AES_BYTES, data.length-AES_BYTES );
				
				return( result );
			
			}else{
				
				AESFastEngine aes = new AESFastEngine();
				
				CBCBlockCipher cbc=new CBCBlockCipher(aes);
				
				PaddedBufferedBlockCipher s = new PaddedBufferedBlockCipher(cbc);
				
				byte[]  IV		= new byte[16];
				
				System.arraycopy( data, 0, IV, 0, 16 );
				
				s.init( false, new ParametersWithIV( new KeyParameter(key), IV ));
				
				byte[] dec = new byte[data.length];
				
				int done = s.processBytes( data, 16, data.length-16, dec, 0);

				done += s.doFinal( dec, done);
				
				byte[] result = new byte[done];
				
				System.arraycopy( dec, 0, result, 0, done );
				
				return( result );
			}
		}catch( Throwable e ){
			
			Debug.out( e );
			
			return( null );
		}
	}
		
	private Map<String,Object>
	handleDHRequest(
		DHTPluginContact		originator,
		Map<String,Object>		request )
	{
		Map<String,Object>		reply_map = new HashMap<String, Object>();

		try{				
			byte[]	act_id	 	= (byte[])request.get("i");
			
			byte[]	user_key 	= (byte[])request.get("u");
	
			byte[]	their_keys	= (byte[])request.get( "k" );

			HashWrapper act_id_wrapper = new HashWrapper( act_id );
			
			CryptoSTSEngine	sts;
			
			synchronized( secret_activities ){
				
				InetSocketAddress address = originator.getAddress();
				
				byte[] bloom_key = ( address.isUnresolved()?address.getHostName():address.getAddress().getHostAddress()).getBytes( "UTF-8" );
				
				if ( secret_activities_bloom.add( bloom_key ) > 8 ){
					
					throw( new IPCException( "Connection refused - address overloaded" ));
				}
				
				Object[] existing = secret_activities.get( act_id_wrapper );
				
				if ( existing == null ){
					
					if ( their_keys == null ){
						
						throw( new IPCException( "Connection expired" ));
					}
					
					if ( secret_activities.size() > 16 ){
						
						throw( new IPCException( "Connection refused - peer overloaded" ));
					}
					
					sts = CoreFactory.getSingleton().getCryptoManager().getECCHandler().getSTSEngine( public_key, private_key );
	
					secret_activities.put( act_id_wrapper, new Object[]{ SystemTime.getMonotonousTime(), sts });
					
				}else{
					
					sts = (CryptoSTSEngine)existing[1];
				}
			}
						
			if ( their_keys != null ){
				
				ByteBuffer buffer = ByteBuffer.allocate( 16*1024 );
				
				sts.getKeys( buffer );
				
				buffer.flip();
				
				byte[]	my_keys = new byte[ buffer.remaining()];
				
				buffer.get( my_keys );
				
				reply_map.put( "k", my_keys );
				
					// stuff in their keys
				
				sts.putKeys( ByteBuffer.wrap( their_keys ));
				
				buffer.position(0);
				
				sts.getAuth( buffer );
				
				buffer.flip();
				
				byte[]	auth = new byte[ buffer.remaining()];
				
				buffer.get( auth );
				
				reply_map.put( "a", auth );
			
			}else{
				
				byte[]	auth = (byte[])request.get( "a" );

				if ( auth == null ){
					
					throw( new Exception( "auth missing" ));
				}
				
				sts.putAuth( ByteBuffer.wrap( auth ));
				
				byte[] shared_secret = fixSecret( sts.getSharedSecret());
				
				byte[]	remote_pk = sts.getRemotePublicKey();
				
				synchronized( secret_activities ){
					
					secret_activities.remove( act_id_wrapper );
				}
								
				MsgSyncHandler chat_handler = plugin.getSyncHandler( dht, this, remote_pk, originator.exportToMap(), user_key, shared_secret );

				boolean	accepted = false;

				try{					
					for ( MsgSyncListener l: listeners ){
						
						String nick = l.chatRequested( remote_pk, chat_handler );
							
						if ( nick != null ){
							
							chat_handler.reportInfoText( "azmsgsync.report.connected.to", nick );
						}
						
						accepted = true;
					}
					
				}finally{
					
					if ( !accepted ){
						
						chat_handler.destroy( true );						
					}
				}
				
				if ( !accepted ){
					
					throw( new IPCException( "Connection not accepted" ));
				}
			}		
		}catch( IPCException e ){
						
			reply_map.put( "error", e.getMessage());
			
		}catch( Throwable e ){
			
			reply_map.put( "error", Debug.getNestedExceptionMessage( e ));
		}
		
		return( reply_map );
	}
	
	
	
	protected boolean
	sync()
	{
		return( sync( false ));
	}
		
	protected boolean
	sync(
		final boolean		prefer_live )
	{
		MsgSyncNode	sync_node = null;
		
		synchronized( node_uid_map ){
			
			if ( parent_handler != null ){
				
				if ( private_messaging_secret == null ){
								
					return( false );
				}
			}
			
			if ( prefer_live ){
				
				prefer_live_sync_outstanding = true;
			}
			
			if ( TRACE )trace( "Sync: active=" + active_syncs );
			
			if ( active_syncs.size() > MAX_CONC_SYNC ){
				
				return( false );
			}
			
			Set<String>	active_addresses = new HashSet<String>();
			
			for ( MsgSyncNode n: active_syncs ){
				
				active_addresses.add( n.getContactAddress());
			}
							
			List<MsgSyncNode>	not_failed 	= new ArrayList<MsgSyncNode>( MAX_NODES*2 );
			List<MsgSyncNode>	failed 		= new ArrayList<MsgSyncNode>( MAX_NODES*2 );
			List<MsgSyncNode>	live 		= new ArrayList<MsgSyncNode>( MAX_NODES*2 );
			
			for ( List<MsgSyncNode> nodes: node_uid_map.values()){
				
				for ( MsgSyncNode node: nodes ){
					
					if ( active_syncs.size() > 0 ){
					
						if ( active_syncs.contains( node ) || active_addresses.contains( node.getContactAddress())){
						
							continue;
						}
					}
										
					if ( node.getFailCount() == 0 ){
						
						not_failed.add( node );
						
						if ( node.getLastAlive() > 0 ){
							
							live.add( node );
						}
					}else{
						
						failed.add( node );
					}
				}
			}
			
			if ( not_failed.size() > 0 ){
				
				random_liveish_node = not_failed.get(RandomUtils.nextInt(not_failed.size()));
			}
			
			MsgSyncNode	current_biased_node_in 	= biased_node_in;
			MsgSyncNode	current_biased_node_out = biased_node_out;
			
			boolean	clear_biased_node_out 	= false;
			boolean	clear_biased_node_in	= false;
			boolean	clear_prefer_live		= false;
			
			if ( current_biased_node_out != null ){
				
					// node_out should be live as we should have just successfully hit it
				
				if ( live.contains( current_biased_node_out )){
								
					sync_node = current_biased_node_out;
					
					if ( TRACE )trace( "Selecting biased node_out " + sync_node.getName());
				}
				
				clear_biased_node_out = true;
				
			}else if ( current_biased_node_in != null ){
			
					// node_in might be alive or unknown at this point
				
				if ( not_failed.contains( current_biased_node_in )){
				
					sync_node = current_biased_node_in;
					
					if ( TRACE )trace( "Selecting biased node_in " + sync_node.getName());
				}
				
				clear_biased_node_in	= true;
				
			}else{
				
				if ( prefer_live_sync_outstanding && live.size() > 0 ){
									
					sync_node = getRandomSyncNode( live );
					
					if ( sync_node != null ){
						
						clear_prefer_live = true;
					}
				}
			}
			
			if ( sync_node == null ){
				
				int	active_fails = 0;
				
				for ( MsgSyncNode node: active_syncs ){
					
					if ( node.getFailCount() > 0 ){
						
						active_fails++;
					}
				}
				
				if ( active_fails >= MAX_FAIL_SYNC && not_failed.size() > 0 ){
					
					sync_node = getRandomSyncNode( not_failed );
				}
				
				if ( sync_node == null ){
					
					sync_node = getRandomSyncNode( failed, not_failed );
				}
			}
			
			if ( TRACE )trace( "    selected " + (sync_node==null?"none":sync_node.getName()));
			
			if ( first_sync_attempt_time == -1 && !checking_dht ){
				
				first_sync_attempt_time = SystemTime.getMonotonousTime();
			}
			
			if ( sync_node == null ){
				
				return( false );
			}
			
			if ( sync_pool.isFull()){
				
				if ( TRACE )trace( "Thread pool is full" );
				
				return( true );
			}
			
			if ( clear_biased_node_out ){
				
				biased_node_out = null;
			}
			
			if ( clear_biased_node_in ){
				
				biased_node_in = null;
			}
			
			if ( clear_prefer_live ){
				
				prefer_live_sync_outstanding = false;
			}
			
			active_syncs.add( sync_node );
		}
						
		final MsgSyncNode	f_sync_node = sync_node;
		
		sync_pool.run(
			new AERunnable()
			{	
				@Override
				public void runSupport() {
					try{
						
						sync( f_sync_node, false );
						
					}finally{
							
						synchronized( node_uid_map ){
							
							active_syncs.remove( f_sync_node );
						}
					}
				}
			});
		
		return( false );
	}
	
	private MsgSyncNode
	getRandomSyncNode(
		List<MsgSyncNode>		nodes1,
		List<MsgSyncNode>		nodes2 )
	{
		List<MsgSyncNode>	nodes = new ArrayList<MsgSyncNode>( nodes1.size() + nodes2.size());
		
		nodes.addAll( nodes1 );
		nodes.addAll( nodes2 );
		
		return( getRandomSyncNode( nodes ));
	}
	
	private MsgSyncNode
	getRandomSyncNode(
		List<MsgSyncNode>		nodes )
	{
		int	num = nodes.size();
		
		if ( num == 0 ){
			
			return( null );
					
		}else{
			
			long	now = SystemTime.getMonotonousTime();
			
			Map<String,Object>	map = new HashMap<String, Object>(num*2);
			
			for ( MsgSyncNode node: nodes ){
				
				String str = node.getContactAddress();
				
				/* removed as didn't help the unreliability situation
				if ( is_anonymous_chat ){
					
					// rate limit these addresses globally to reduce tunnel load, especially
					// when running private chats on an already small chat.
				
					synchronized( anon_dest_use_map ){
						
						Long	last = anon_dest_use_map.get( str );
						
						if ( last != null && now - last < ANON_DEST_USE_MIN_TIME ){
												
							continue;
						}						
					}
				}
				*/
				
				Object x = map.get( str );
				
				if ( x == null ){
					
					map.put( str, node );
					
				}else if ( x instanceof MsgSyncNode ){
					
					List<MsgSyncNode> list = new ArrayList<MsgSyncNode>(10);
					
					list.add((MsgSyncNode)x);
					list.add( node );
					
					map.put( str, list );
					
				}else{
					
					((List<MsgSyncNode>)x).add( node );
				}
			}
			
			if ( map.size() == 0 ){
				
				return( null );
			}
			
			int	index = RandomUtils.nextInt( map.size());
			
			Iterator<Object>	it = map.values().iterator();
			
			for ( int i=0;i<index;i++){
				
				it.next();
			}
			
			Object result = it.next();
			
			MsgSyncNode sync_node;
			
			if ( result instanceof MsgSyncNode ){
				
				sync_node = (MsgSyncNode)result;
				
			}else{
				
				List<MsgSyncNode>	list = (List<MsgSyncNode>)result;
				
				sync_node = list.get( RandomUtils.nextInt( list.size()));
			}
			
			/*
			if ( is_anonymous_chat ){
				
				synchronized( anon_dest_use_map ){
				
					String str = sync_node.getContactAddress();
										
					anon_dest_use_map.put( str, now );
				}
			}
			*/
			
			return( sync_node );
		}
	}
	
	private class
	BloomDetails
	{
		private final long				create_time = SystemTime.getMonotonousTime();
		private final int				mutation_id;
		private final byte[]			rand;
		private final BloomFilter		bloom;
		
		private final ByteArrayHashMap<List<MsgSyncNode>>	msg_node_map;
		
		private final List<MsgSyncNode>	all_public_keys;

		private final int				message_count;
		private final int				new_message_count;
		
		private final long				oldest_message_timestamp;
		
		private
		BloomDetails(
			int									_mutation_id,
			byte[]								_rand,
			BloomFilter							_bloom,
			ByteArrayHashMap<List<MsgSyncNode>>	_msg_node_map,
			List<MsgSyncNode>					_all_public_keys,
			int									_message_count,
			int									_new_message_count,
			long								_oldest_message_timestamp )
		{
			mutation_id					= _mutation_id;
			rand						= _rand;
			bloom						= _bloom;
			msg_node_map				= _msg_node_map;
			all_public_keys				= _all_public_keys;
			message_count				= _message_count;
			new_message_count			= _new_message_count;
			oldest_message_timestamp	= _oldest_message_timestamp;
		}
	}
	
	private BloomDetails	last_bloom_details;
	
	private BloomDetails
	buildBloom()
	{
		synchronized( message_lock ){

			if ( 	last_bloom_details != null && 
					last_bloom_details.mutation_id == message_mutation_id &&
					SystemTime.getMonotonousTime() - last_bloom_details.create_time < 30*1000 ){
				
				return( last_bloom_details );
			}
						
			byte[]		rand 	= new byte[8];
			BloomFilter	bloom	= null;
			
			ByteArrayHashMap<List<MsgSyncNode>>	msg_node_map = null;
			
			List<MsgSyncNode>	all_public_keys = new ArrayList<MsgSyncNode>();

			int	message_count;
					

			message_count = messages.size();
			
			long oldest_timestamp;
			
			if ( message_count == MAX_MESSAGES ){
				
				oldest_timestamp = messages.getFirst().getTimestamp();
				
			}else{
				
				oldest_timestamp = 0;
			}
			
			for ( int i=0;i<64;i++){
				
				RandomUtils.nextSecureBytes( rand );
				
					// slight chance of duplicate keys (maybe two nodes share a public key due to a restart)
					// so just use distinct ones
				
				ByteArrayHashMap<String>	bloom_keys = new ByteArrayHashMap<String>();
				
				Set<MsgSyncNode>	done_nodes = new HashSet<MsgSyncNode>();
				
				msg_node_map = new ByteArrayHashMap<List<MsgSyncNode>>();
					
				for ( MsgSyncMessage msg: messages ){
					
					MsgSyncNode n = msg.getNode();
					
					if ( !done_nodes.contains( n )){
						
						byte[] nid = n.getUID();
						
						List<MsgSyncNode> list = msg_node_map.get( nid );
						
						if ( list == null ){
							
							list = new ArrayList<MsgSyncNode>();
							
							msg_node_map.put( nid, list );
						}
						
						list.add( n );
						
						done_nodes.add( n );
						
						byte[] pub = n.getPublicKey();
						
						if ( pub != null ){
						
							if ( i == 0 ){
								
								all_public_keys.add( n );
							}
							
							try{
								byte[] ad = n.getContactAddress().getBytes( "UTF-8" );
								
								byte[] pk_ad = new byte[pub.length + ad.length];
								
								System.arraycopy( pub, 0, pk_ad, 0, pub.length );
								System.arraycopy( ad, 0, pk_ad, pub.length, ad.length );
								
								for ( int j=0;j<rand.length;j++){
									
									pk_ad[j] ^= rand[j];
								}
								
								bloom_keys.put( pk_ad, "" );
								
							}catch( Throwable e ){
								
								Debug.out( e );
							}
						}
					}
					
					byte[]	sig = msg.getSignature().clone();
		
					for ( int j=0;j<rand.length;j++){
						
						sig[j] ^= rand[j];
					}
					
					bloom_keys.put( sig, ""  );
				}
				
				for ( HashWrapper hw: deleted_messages_inverted_sigs_map.keySet()){
					
					byte[] inv_sig = hw.getBytes().clone();
					
					for ( int j=0;j<rand.length;j++){
						
						inv_sig[j] ^= rand[j];
					}

					bloom_keys.put( inv_sig, ""  );
				}
				
					// in theory we could have 64 sigs + 64 pks -> 128 -> 1280 bits -> 160 bytes + overhead
				
				int	bloom_bits = bloom_keys.size() * 10 + RandomUtils.nextInt( 19 );
					
				if ( bloom_bits < MIN_BLOOM_BITS ){
				
					bloom_bits = MIN_BLOOM_BITS;
				}
				
				bloom = BloomFilterFactory.createAddOnly( bloom_bits );
				
				for ( byte[] k: bloom_keys.keys()){
					
					if ( bloom.contains( k )){
						
						bloom = null;
						
						break;
						
					}else{
						
						bloom.add( k );
					}
				}
				
				if ( bloom != null ){
					
					break;
				}
			}		
		
			if ( bloom == null ){
				
				if ( Constants.isCVSVersion()){
					
					Debug.out( "Bloom construction failed" );
				}
			}
			
			last_bloom_details = new BloomDetails( message_mutation_id, rand, bloom, msg_node_map, all_public_keys, message_count, message_new_count, oldest_timestamp );
			
			return( last_bloom_details );
		}	
	}
	
	private int
	receiveMessages(
		MsgSyncNode						originator,
		BloomDetails					bloom_details,
		List<Map<String,Object>>		list )
	{
			// prevent multiple concurrent replies using a cached bloom filter state from
			// interfering with one another during message extraction
		
		byte[]	originator_key = null;
		
		int	total_received = 0;
		
		synchronized( bloom_details ){
			
			ByteArrayHashMap<List<MsgSyncNode>>	msg_node_map = bloom_details.msg_node_map;
			
			List<MsgSyncNode>	all_public_keys = bloom_details.all_public_keys;
				
				// don't bother with this until its a busy channel
			
			boolean	record_history = bloom_details.new_message_count > MAX_MESSAGES;

			for ( Map<String,Object> m: list ){
				
				try{
					Set<MsgSyncNode>		keys_to_try = null;
					
					byte[] node_uid		= (byte[])m.get( "u" );
					byte[] message_id 	= (byte[])m.get( "i" );
					byte[] content		= (byte[])m.get( "c" );
					byte[] control 		= (byte[])m.get( "$" );
					byte[] signature	= (byte[])m.get( "s" );
					byte[] old_history	= (byte[])m.get( "h" );
										
					byte[] new_history;
					
					if ( record_history ){
						
						if ( originator_key == null ){
							
							originator_key = new byte[4];

							try{
								byte[] temp = new SHA1Simple().calculateHash( originator.getContactAddress().getBytes( "UTF-8" ));
							
								System.arraycopy( temp, 0, originator_key, 0, 4 );
								
							}catch( Throwable e ){
								
								Debug.out( e );
							}
						}
						
						if ( old_history == null || old_history.length == 0 ){
							
							new_history	= originator_key;
							
						}else{
							
							int	old_len = old_history.length;
							
							if ( old_len > MAX_HISTORY_RECORD_LEN - 4 ){
								
								new_history = new byte[MAX_HISTORY_RECORD_LEN];
								
								System.arraycopy( originator_key, 0, new_history, 0, 4 );
								
								System.arraycopy( old_history, 0, new_history, 4, MAX_HISTORY_RECORD_LEN-4 );
								
							}else{
	
								new_history = new byte[old_len + 4];
								
								System.arraycopy( originator_key, 0, new_history, 0, 4 );
								
								System.arraycopy( old_history, 0, new_history, 4, old_len );
							}
						}
					}else{
						
						new_history = old_history;
					}
					
					int	age = ((Number)m.get( "a" )).intValue();
							
						// these won't be present if remote believes we already have it (subject to occasional bloom false positives)
					
					byte[] 	public_key		= (byte[])m.get( "p" );
					
					Map<String,Object>		contact_map		= (Map<String,Object>)m.get( "k" );
													
						//log( "Message: " + ByteFormatter.encodeString( message_id ) + ": " + new String( content ) + ", age=" + age );
													
					boolean handled 	= false;
					boolean	new_message	= false;
					
						// see if we already have a node with the correct public key
					
					List<MsgSyncNode> nodes = msg_node_map.get( node_uid );
					
					if ( nodes != null ){
						
						for ( MsgSyncNode node: nodes ){
							
							byte[] pk = node.getPublicKey();
							
							if ( pk != null ){
								
								if ( keys_to_try == null ){
									
									keys_to_try = new HashSet<MsgSyncNode>( all_public_keys );
								}
								
								keys_to_try.remove( node );
								
								Signature sig = CryptoECCUtils.getSignature( CryptoECCUtils.rawdataToPubkey( pk ));
								
								sig.update( node_uid );
								sig.update( message_id );
								sig.update( content );
								
								if ( control != null ){
									sig.update( control );
								}
								
								if ( sig.verify( signature )){
																		
									new_message = addMessage( node, message_id, content, control, signature, age, new_history, contact_map, MS_INCOMING );
									
									handled = true;
									
									break;
								}
							}
						}
					}
						
					if ( !handled ){
						
						if ( public_key == null ){
							
								// the required public key could be registered against another node-id
								// in this case the other side won't have returned it to us but we won't
								// find it under the existing set of keys associated with the node-id
							
							if ( keys_to_try == null ){
								
								keys_to_try = new HashSet<MsgSyncNode>( all_public_keys );
							}
							
							for ( MsgSyncNode n: keys_to_try ){
								
								byte[] pk = n.getPublicKey();
								
								Signature sig = CryptoECCUtils.getSignature( CryptoECCUtils.rawdataToPubkey( pk));
								
								sig.update( node_uid );
								sig.update( message_id );
								sig.update( content );
								
								if ( control != null ){
									sig.update( control );
								}
								
								if ( sig.verify( signature )){
																			
										// dunno if the contact has changed so all we can do is use the existing
										// one associated with this key
									
									MsgSyncNode msg_node = addNode( n.getContact(), node_uid, pk );
									
									new_message = addMessage( msg_node, message_id, content, control, signature, age, new_history, contact_map, MS_INCOMING );
								
									handled = true;
																	
									break;
								}
							}
						}else{
							
								// no existing pk - we HAVE to record this message against
								// this supplied pk otherwise we can't replicate it later
	
							Signature sig = CryptoECCUtils.getSignature( CryptoECCUtils.rawdataToPubkey( public_key ));
							
							sig.update( node_uid );
							sig.update( message_id );
							sig.update( content );
							
							if ( control != null ){
								sig.update( control );
							}
							
							if ( sig.verify( signature )){
																	
								DHTPluginContact contact = dht.importContact( contact_map );
								
									// really can't do anything if contact deserialiseation fails
								
								if ( contact != null ){
									
									MsgSyncNode msg_node = null;
									
										// look for existing node without public key that we can use
									
									if ( nodes != null ){
										
										for ( MsgSyncNode node: nodes ){
											
											if ( node.setDetails( contact, public_key )){
												
												msg_node = node;
												
												break;
											}
										}
									}
									
									if ( msg_node == null ){
									
										msg_node = addNode( contact, node_uid, public_key );
										
											// save so local list so pk available to other messages
											// in this loop
										
										List<MsgSyncNode> x = msg_node_map.get( node_uid );
										
										if ( x == null ){
											
											x = new ArrayList<MsgSyncNode>();
											
											msg_node_map.put( node_uid, x );
										}
										
										x.add( msg_node );
									}
										
									all_public_keys.add( msg_node );
									
									new_message = addMessage( msg_node, message_id, content, control, signature, age, new_history, contact_map, MS_INCOMING );
									
									handled = true;
								}
							}
						}
					}
					
					if ( new_message && handled ){
						
						total_received++;
					}
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
		}
		
		return( total_received );
	}
	
	private void
	reportSpamStatus()
	{
		synchronized( message_lock ){
		
			if ( spammer_map.size() == 0 ){
				
				return;
			}
							
			String	msg = "Spam: " + spammer_map.size() + " key(s) identified";
			
			String banned = "";
			
			for ( HashWrapper2 hw: spammer_bad_keys ){
				
				banned += (banned==""?"":", ") + ByteFormatter.encodeString( hw.getBytes(), hw.getOffset(), hw.getLength());

			}
			
			if ( banned != "" ){
				
				msg += "\n    Banned: " + banned;
			}
			
			reportInfoRaw( msg );
		}
	}
	
	private void
	reportHistoryStatus()
	{
		synchronized( message_lock ){
		
			String msg = "Banning " + (node_banning_enabled?"enabled":"disabled" );
			
			if ( node_banning_enabled ){
				
				msg += "\nWatching " + history_watch_map.size() + " node(s)";
				
				String banned = "";
				
				for ( HashWrapper hw: history_bad_keys ){
					
					banned += (banned==""?"":", ") + ByteFormatter.encodeString( hw.getBytes());
				}
				
				if ( banned != "" ){
					
					msg += "\n    Banned: " + banned;
				}
			}
			
			reportInfoRaw( msg );
		}
	}
	
	private void
	resetHistories()
	{
		synchronized( message_lock ){
						
			history_key_bloom	= null;
			
			history_watch_map.clear();
			history_bad_keys.clear();
		}
	}
	
	private void
	resetHistories(
		boolean		enable )
	{
		synchronized( message_lock ){
			
			node_banning_enabled = enable;
			
			resetHistories();
		}
	}
	
	private void
	checkHistories()
	{
		synchronized( message_lock ){

			Iterator<Map.Entry<HashWrapper,HistoryWatchEntry>>	it = history_watch_map.entrySet().iterator();
			
			while( it.hasNext()){
				
				Map.Entry<HashWrapper,HistoryWatchEntry> entry = it.next();
				
				if ( entry.getValue().canDelete()){
					
					it.remove();
				}
			}
		}
	}
	
	private boolean
	historyReceived(
		byte[]		originator_pk,
		byte[]		history )
	{
		synchronized( message_lock ){
				
			if ( spammer_map.size() > 0 ){
				
				SpammerEntry spam = spammer_map.get( new HashWrapper2( originator_pk ));
				
				if ( spam != null ){
					
						// direct hit, however we don't believe this as someone could be spamming
						// by re-injecting someone else's messages
					
					spam.addRecord( history );
				}
				
					// look for the currently deduced set of bad keys
				
				int	len = history.length & 0x0000fffc;
				
				for ( int i=0; i<len; i+=4 ){
								
					HashWrapper2	hkey = new HashWrapper2( history, i, 4 );
	
					if ( spammer_bad_keys.contains( hkey )){
						
						return( false );
					}
				}
			}
			
			if ( !node_banning_enabled ){
				
				return( true );
			}
			
			if ( message_new_count <= MAX_MESSAGES ){
				
				return( true );
			}
			
			long now = SystemTime.getMonotonousTime();

			if ( 	history_key_bloom != null && 
					history_key_bloom.getEntryCount() > history_key_bloom_size/10 ){
				
				history_key_bloom_size += 1024;
				
				history_key_bloom = null;
			}
			
			if ( history_key_bloom == null || now - history_key_bloom_create_time > 60*1000 ){
				
				history_key_bloom_create_time = now;
				
				history_key_bloom = BloomFilterFactory.createAddRemove4Bit( history_key_bloom_size );
			}
			
			int	len = history.length & 0x0000fffc;
			
			for ( int i=0; i < len; i+=4 ){
							
				HashWrapper	hkey = new HashWrapper( history, i, 4 );
				
				if ( history_bad_keys.contains( hkey )){
					
					return( false );
				}
				
				HistoryWatchEntry watch_entry = history_watch_map.get( hkey );
				
				if ( watch_entry == null ){
					
					int count = history_key_bloom.add( hkey.getBytes());
					
					if ( count > 5 ){
																			
						watch_entry = new HistoryWatchEntry( hkey );
							
						history_watch_map.put( hkey, watch_entry );
						
						if ( !watch_entry.addRecord( history, i )){
							
							break;
						}
					}
				}else{
					
					if ( !watch_entry.addRecord( history, i )){
						
						break;
					}
				}
			}
		}
		
		return( true );
	}
	
	private void
	computeSpamStatus()
	{
		List<byte[]>	histories = new ArrayList<byte[]>();
		
		for ( SpammerEntry entry: spammer_map.values()){
			
			histories.addAll( entry.getHistories());
		}
		
		Map<HashWrapper2,int[]>	key_counts = new HashMap<HashWrapper2, int[]>();
		
		for ( byte[] history: histories ){
			
			int	len = history.length & 0x0000fffc;
			
			for ( int i=0; i<len; i+=4 ){
							
				HashWrapper2	hkey = new HashWrapper2( history, i, 4 );
				
				int[] c = key_counts.get( hkey );
				
				if ( c == null ){
					
					c = new int[]{ 1 };
					
					key_counts.put( hkey, c );
					
				}else{
					
					c[0]++;
				}
			}
		}
		
		spammer_bad_keys.clear();
		
		for ( byte[] history: histories ){
			
			int	len = history.length & 0x0000fffc;
			
			int	max 				= 0;
			HashWrapper2 max_hash 	= null;
			
			for ( int i=0; i<len; i+=4 ){
							
				HashWrapper2	hkey = new HashWrapper2( history, i, 4 );
				
				int[] c = key_counts.get( hkey );
				
				if ( c[0] > max ){
					
					max			= c[0];
					max_hash 	= hkey;
				}
			}
			
			if ( max > 1 ){
				
				spammer_bad_keys.add( max_hash );
				
			}else{
				
				for ( int i=0; i<len; i+=4 ){
					
					HashWrapper2	hkey = new HashWrapper2( history, i, 4 );
					
					spammer_bad_keys.add( hkey );
				}
			}
		}
	}
	
	private class
	SpammerEntry
	{
		private byte[]		public_key;
			
		private LinkedList<byte[]>	histories = new LinkedList<byte[]>();
		private
		SpammerEntry(
			byte[]		pk )
		{
			public_key		= pk;
		}
		
		private void
		addRecord(
			byte[]	history )
		{
			//System.out.println( "addRecord: " + ByteFormatter.encodeString( public_key ) + "/" +  ByteFormatter.encodeString( history ));
			
			if ( history == null || history.length == 0 ){
				
				return;
			}
			
			synchronized( message_lock ){
				
				histories.add( history );
				
				if ( histories.size() > 16 ){
					
					histories.removeFirst();
				}
				
				computeSpamStatus();
			}
		}
		
		private List<byte[]>
		getHistories()
		{
			return( histories );
		}
		
		public void
		destroy()
		{
			//System.out.println( "Destroy: " + ByteFormatter.encodeString( public_key ));
			
			synchronized( message_lock ){
				
				computeSpamStatus();
			}
		}
	}
	
	private class
	HistoryWatchEntry
	{
		private static final int MINUTE_BAN_MSG_AVERAGE		= 30;
		private static final int TWO_MINUTE_BAN_MSG_AVERAGE	= 50;
		
		private long			create_time	= SystemTime.getMonotonousTime();
		
		private HashWrapper		key;
		
		private com.biglybt.core.util.Average minute_average 	= com.biglybt.core.util.Average.getInstance(60000, 60*2);
		private com.biglybt.core.util.Average two_minute_average = com.biglybt.core.util.Average.getInstance(120000, 120*2);
		
		private Set<HashWrapper>	tail_enders = new HashSet<HashWrapper>();
		
		private
		HistoryWatchEntry(
			HashWrapper		_key )
		{
			key		= _key;
			
			//System.out.println( "new watch: " + getKeyString());
		}
		
		private boolean
		addRecord(
			byte[]		history,
			int			key_offset )
		{
			minute_average.addValue( 60 );
			two_minute_average.addValue( 120 );
			
				// flooder could inject bogus history entries in attempt to get other people banned
			
			for ( int i=key_offset+4; i<history.length; i+=4 ){
				
				tail_enders.add( new HashWrapper( history, i, 4 ));
			}
			
			//System.out.println( getKeyString() + ": add -> " + minute_average.getAverage() + "/" + two_minute_average.getAverage());

			if ( minute_average.getAverage() > MINUTE_BAN_MSG_AVERAGE || two_minute_average.getAverage() > TWO_MINUTE_BAN_MSG_AVERAGE ){
				
				history_bad_keys.add( key );
				
				history_watch_map.remove( key );
				
				for ( HashWrapper te: tail_enders ){
					
					if ( history_bad_keys.contains( te )){
						
						history_bad_keys.remove( te );
					}
					
					history_watch_map.remove( te );
				}
				
				reportErrorRaw( 
					"Node '" + getKeyString() + 
					"' has been banned due to flooding\nTo unban the node enter '/control unban " + getKeyString() + 
					"'. See http://wiki.biglybt.com/w/Decentralized_Chat#Control_Commands[[Control%20Commands]] for more information." );
				
				return( false );
				
			}else{
				
				return( true );
			}
		}
		
		private boolean
		canDelete()
		{
			if ( SystemTime.getMonotonousTime() - create_time > 3*60*1000 ){
				
				//System.out.println( "del watch as boring: " + getKeyString());
				
				return( minute_average.getAverage() < MINUTE_BAN_MSG_AVERAGE/2 && two_minute_average.getAverage() < TWO_MINUTE_BAN_MSG_AVERAGE/2 );
				
			}else{
				
				return( false );
			}
		}
		
		private String
		getKeyString()
		{
			return( ByteFormatter.encodeString( key.getBytes()));
		}
	}
	
	private void
	sync(
		final MsgSyncNode		sync_node,
		boolean					no_tunnel )
	{

		if ( !( no_tunnel || is_anonymous_chat )){
						
			boolean node_dead = sync_node.getFailCount() > 0 || sync_node.getLastAlive() == 0;
			
			if ( node_dead ){
								
				boolean	try_tunnel;
				boolean	force;
				
				if ( is_private_chat ){
					
					try_tunnel 	= true;
					force		= true;
					
				}else{
					
					force = false;
										
					if ( sync_node.getRendezvous() != null ){
						
						try_tunnel = true;
						
					}else{
						
						int[] node_counts = getNodeCounts( false );
						
						int	total 	= node_counts[0];
						int live	= node_counts[1];
						
						try_tunnel = ( live == 0 ) || ( total <= 5 && live < 2 ) || ( total <= 10 && live < 3 );
					}
				}
				
				if ( try_tunnel ){
					
					tryTunnel(
						sync_node,
						force,
						new Runnable()
						{
							@Override
							public void
							run()
							{
								sync( sync_node, true );
							}
						});
				}
			}
		}
		
		BloomDetails bloom_details = buildBloom();
		
		BloomFilter	bloom	= bloom_details.bloom;
	
		if ( bloom == null ){
			
				// clashed too many times, whatever, we'll try again soon
			
			return;
		}
		
		byte[]		rand 	= bloom_details.rand;
		int	message_count 	= bloom_details.message_count;

		
		Map<String,Object> request_map = new HashMap<String,Object>();
		
		request_map.put( "v", VERSION );
		
		request_map.put( "t", RT_SYNC_REQUEST );		// type

		request_map.put( "u", my_uid );
		
		byte[]	request_id = new byte[6];
		
		RandomUtils.nextBytes( request_id );
		
		request_map.put( "q", request_id );
		
		request_map.put( "b", bloom.serialiseToMap());
		request_map.put( "r", rand );
		request_map.put( "m", message_count );
		request_map.put( "p", bloom_details.new_message_count );
			
		long ot = bloom_details.oldest_message_timestamp;
		
		if ( ot > 0 ){
			
			long now = SystemTime.getCurrentTime();
					
			if ( now > ot ){
					
				int	oldest_age_secs = (int) (( now - ot ) / 1000 );
								
				request_map.put( "o", oldest_age_secs );
			}
		}
		
		if ( !is_anonymous_chat ){
			
			try{
				if ( dht instanceof DHTPlugin ){
					
					DHTPlugin dht_plugin = (DHTPlugin)dht;
					
					DHT core_dht = dht_plugin.getDHT( DHTPlugin.NW_AZ_MAIN );
					
					if ( core_dht != null ){
						
						DHTNATPuncher nat_puncher = core_dht.getNATPuncher();
						
						if ( nat_puncher != null ){
							
							DHTTransportContact rendezvous = nat_puncher.getRendezvous();
							
							if ( rendezvous != null ){
								
								request_map.put( "z", rendezvous.exportContactToMap());
							}
						}
					}
				}
			}catch( Throwable e ){
					
				Debug.out( e );
			}
		}
		
		try{
			byte[]	sync_data = BEncoder.encode( request_map );
			
			if ( private_messaging_secret != null ){
				
				sync_data = privateMessageEncrypt( sync_data );
				
			}else{
								
				sync_data = generalMessageEncrypt( sync_data );
			}
			
			// long	start = SystemTime.getMonotonousTime();
						
			byte[] reply_bytes = 
				sync_node.getContact().call(
					new DHTPluginProgressListener() {
						
						@Override
						public void reportSize(long size) {
						}
						
						@Override
						public void reportCompleteness(int percent) {
						}
						
						@Override
						public void reportActivity(String str) {
						}
					},
					dht_call_key,
					sync_data, 
					30*1000 );
		
			// if (TRACE )trace( "Call took " + ( SystemTime.getMonotonousTime() - start ));
			
			if ( reply_bytes == null ){

				throw( new Exception( "Timeout - no reply" ));
			}
			
			if ( private_messaging_secret != null ){
				
				reply_bytes = privateMessageDecrypt( reply_bytes );
				
			}else{
								
				reply_bytes = generalMessageDecrypt( reply_bytes );
			}
			
			out_req_ok++;
			
			Map<String,Object> reply_map = BDecoder.decode( reply_bytes );

			int	type = reply_map.containsKey( "t" )?((Number)reply_map.get( "t" )).intValue():-1; 

			if ( type != RT_SYNC_REPLY ){
				
					// meh, issue with 'call' implementation when made to self - you end up getting the
					// original request data back as the result :( Can't currently see how to easily fix
					// this so handle it for the moment. update - fixed so shouldn't be seeing this issue now
				
				removeNode( sync_node, true );
				
			}else{
				
				if ( TRACE )trace( "reply: " + reply_map + " from " + sync_node.getName());
				
				int status = ((Number)reply_map.get( "s" )).intValue();
				
				if ( status == STATUS_LOOPBACK ){
					
					removeNode( sync_node, true );
					
				}else{
					
					sync_node.ok();
					
					nodeIsAlive( sync_node );

					last_successful_sync_time = SystemTime.getMonotonousTime();
					
					List<Map<String,Object>>	list = (List<Map<String,Object>>)reply_map.get( "m" );
					
					if ( list == null ){
						
						byte[]	compressed = (byte[])reply_map.get( "z" );
						
						if ( compressed != null ){
							
							ByteArrayInputStream bais = new ByteArrayInputStream( compressed );
							
							GZIPInputStream zip = new GZIPInputStream( bais );
													
							Map temp = BDecoder.decode( new BufferedInputStream( zip, MAX_MESSSAGE_REPLY_SIZE ));
							
							list = (List<Map<String,Object>>)temp.get( "m" );
						}
					}
					
					int	received;
					
					if ( list != null ){
						
						received = receiveMessages( sync_node, bloom_details, list );
						
					}else{
						
						received = 0;
					}
					
					Number n_more_to_come = (Number)reply_map.get( "x" );
											
					int	more_to_come = n_more_to_come==null?0:n_more_to_come.intValue();
					
					last_more_to_come = more_to_come;
					
					if ( more_to_come > 0 ){
						
						consec_no_more_to_come = 0;
						
						if ( received >= 2 ){
										
							byte[] bk = sync_node.getContactAddress().getBytes( "UTF-8" );
							
							synchronized( biased_node_bloom ){
								
									// important we don't allow a node to repeatedly bias itself else
									// it can organise a flood to go via a single node and masquarade behind it
								
								if ( biased_node_out == null && !biased_node_bloom.contains( bk )){
									
									biased_node_bloom.add( bk );
									
									if ( TRACE )trace( "Proposing biased node_out " + sync_node.getName());

									biased_node_out = sync_node;
								}
							}
						}
					}else{
						
						consec_no_more_to_come++;
					}
					
					Map rln = (Map)reply_map.get( "n" );
					
					if ( rln != null ){
						
						byte[]	uid = (byte[])rln.get( "u" );
						Map		c	= (Map)rln.get( "c" );
						
						DHTPluginContact contact = dht.importContact( c );
						
						if ( contact != null ){
						
							addNode( contact, uid, null );
						}
					}
				}
			}
		}catch( Throwable e ){
			
			//if ( TRACE )trace(e.getMessage());
			
			out_req_fail++;
			
			sync_node.failed();
		}
	}	

	@Override
	public byte[]
	handleRead(
		DHTPluginContact	originator,
		byte[]				key )
	{
		if ( destroyed ){
			
			return( null );
		}
			
		updateProtocolCounts( originator.getAddress());
		
		if ( private_messaging_secret != null ){
			
			key = privateMessageDecrypt( key );
			
		}else{
							
			key = generalMessageDecrypt( key );
		}
		
		if ( key == null ){
			
			return( null );
		}
		
		try{
			Map<String,Object> request_map = BDecoder.decode( key );
			
			int	type = request_map.containsKey( "t" )?((Number)request_map.get( "t" )).intValue():-1; 

			if ( type == RT_DH_REQUEST ){
				
				Map<String,Object>	reply_map = handleDHRequest( originator, request_map );
				
				reply_map.put( "s", status );
				
				reply_map.put( "t", RT_DH_REPLY );
				
				byte[] reply_bytes = BEncoder.encode( reply_map );
				
				reply_bytes = generalMessageEncrypt( reply_bytes );

				return( reply_bytes );
			}
			
			if ( type != RT_SYNC_REQUEST ){
				
 				return( null );
			}

			int	caller_version = ((Number)request_map.get( "v" )).intValue();
			
			if ( caller_version < MIN_VERSION ){
								
				return( null );
			}

			byte[]	rand = (byte[])request_map.get( "r" );

			byte[]	request_id = (byte[])request_map.get( "q" );
			
			HashWrapper request_id_wrapper = new HashWrapper( request_id==null?rand:request_id );
			
			synchronized( request_id_history ){
				
				if ( request_id_history.get( request_id_wrapper ) != null ){
				
					if ( TRACE )trace( "duplicate request: " + ByteFormatter.encodeString( request_id_wrapper.getBytes(), 0, 4 ) + " - " + request_map + " from " + getString( originator ));
					
					return( null );
				}
				
				request_id_history.put( request_id_wrapper, "" );
			}
			
			if ( TRACE )trace( "request: " + request_map + " from " + getString( originator ));

			in_req++;
			
			Map<String,Object> reply_map = new HashMap<String,Object>();

			int		status;
			int		more_to_come = 0;

			byte[]	uid = (byte[])request_map.get( "u" );

			if ( Arrays.equals( my_uid, uid )){
				
				status = STATUS_LOOPBACK;
				
			}else{
				
				/*
				if ( originator.getAddress().isUnresolved()){
					
					trace( "unresolved" );
				}
				*/
				
				status = STATUS_OK;
				
				Number	n_messages_they_have 	= (Number)request_map.get( "m" );
				Number	n_oldest_age 			= (Number)request_map.get( "o" );
				
				//System.out.println( message_new_count + ": " +  m_temp + "/" + request_map.get( "p" ) + "/" + request_map.get( "n" ));
				
				int	messages_they_have 	= n_messages_they_have==null?-1:n_messages_they_have.intValue();
				int	oldest_age			= n_oldest_age==null?0:n_oldest_age.intValue();
				
				int messages_hidden = 0;
				
				if ( oldest_age < 0 ){
					
					oldest_age = 0;
				}
				
				List<MsgSyncNode> caller_nodes = getNodes( uid );
				
				MsgSyncNode originator_node = null;
				
				if ( caller_nodes != null ){
					
					for ( MsgSyncNode n: caller_nodes ){
						
						if ( sameContact( originator, n.getContact())){
							
							originator_node = n;
							
							break;
						}
					}
				}
				
				if ( originator_node == null  ){
					
					originator_node = addNode( originator, uid, null );
				}
				
				nodeIsAlive( originator_node );

				BloomFilter bloom = BloomFilterFactory.deserialiseFromMap((Map<String,Object>)request_map.get("b"));
								
				List<MsgSyncMessage>	missing = new ArrayList<MsgSyncMessage>();
				
				int messages_we_have;
				
				int	messages_we_have_they_deleted = 0;
				
				synchronized( message_lock ){
					
					messages_we_have = messages.size();
					
					List<MsgSyncMessage>	messages_we_both_have = new ArrayList<MsgSyncMessage>( messages_we_have );

					for ( MsgSyncMessage msg: messages ){
						
						byte[]	sig = msg.getSignature().clone();	// clone as we mod it
			
						for ( int i=0;i<rand.length;i++){
							
							sig[i] ^= rand[i];
						}
						
						byte[] del_sig = sig.clone();
						
						for ( int i=0;i<del_sig.length;i++){
							
							del_sig[i] ^= 0xff;
						}
						
						if ( bloom.contains( sig )){

							messages_we_both_have.add( msg );
							
							msg.probablySeen();
							
						}else if ( bloom.contains( del_sig )){
							
							messages_we_have_they_deleted++;
							
						}else{
						
								// I have it, they don't
							
								// don't return any messages that are going to be discarded by the
								// caller as they are older than the oldest message they have and
								// they have max messages. this helps with users that have been offline
								// for a while on a reasonably active channel and not yet resynced
							
							boolean	too_old = false;
							
							if ( oldest_age > 0 ){
								
								int	msg_age = msg.getAgeSecs();
							
								too_old = msg_age - oldest_age >= 5*60;
							}

							if ( !too_old ){
							
								if ( caller_version < 5 && msg.getControl() != null ){
								
									// caller can't handle the additional control component of sig, hide it
									
								}else{
								
									if ( plugin.isGlobalBan( msg )){
									
										messages_hidden++;
										
										msg.seen();
										
										msg.delivered();
										
									}else{
										
										missing.add( msg );
									}
								}
							}
						}
					}
					
					if ( messages_they_have >= messages_we_have && messages_we_have_they_deleted == 0 ){
						
						// just in case we have a bloom clash and they don't really have
						// the message, double check that they have at least as many
						// messages as us
					
						for ( MsgSyncMessage msg: messages_we_both_have ){
						
							msg.seen();
						}
					}
				}
					
				if ( missing.size() > 0 ){
					
					Set<MsgSyncNode>	done_nodes = new HashSet<MsgSyncNode>();
					
					List<Map<String,Object>> l = new ArrayList<Map<String,Object>>();
										
					int	content_control_bytes = 0;
										
					for ( MsgSyncMessage message: missing ){

						if ( message.getMessageType() != MsgSyncMessage.ST_NORMAL_MESSAGE ){
							
								// local/invalid message, don't propagate
						
							continue;
						}
						
						byte[]	content = message.getContent();
						byte[] 	control = message.getControl();

						int	control_length = control==null?0:control.length;
						
						if ( content_control_bytes + content.length + control_length > MAX_MESSSAGE_REPLY_SIZE ){
							
							more_to_come++;
							
							continue;
						}
						
						content_control_bytes += content.length + control_length;
																		
						if ( TRACE )trace( "    returning " + ByteFormatter.encodeString( message.getID()));
						
						Map<String,Object> m = new HashMap<String,Object>();
						
						l.add( m );
						
						MsgSyncNode	n = message.getNode();					

						m.put( "u", n.getUID());
						m.put( "i", message.getID());
						m.put( "c", content );
						m.put( "s", message.getSignature());
						m.put( "a", message.getAgeSecs());
						m.put( "h", message.getHistory());
						
						
						if ( control != null ){
							m.put( "$", control );
						}
						
						message.delivered();
						
						if ( !done_nodes.contains( n )){
							
							done_nodes.add( n );
							
							byte[]	pub = n.getPublicKey();	
							
							if ( pub != null ){
							
								try{
									byte[] ad = n.getContactAddress().getBytes( "UTF-8" );
									
									byte[] pk_ad = new byte[pub.length + ad.length];
									
									System.arraycopy( pub, 0, pk_ad, 0, pub.length );
									System.arraycopy( ad, 0, pk_ad, pub.length, ad.length );
									
									for ( int i=0;i<rand.length;i++){
										
										pk_ad[i] ^= rand[i];
									}
									
									if ( !bloom.contains( pk_ad )){
										
										if ( TRACE )trace( "    and pk" );
										
										m.put( "p", n.getPublicKey());
										
										DHTPluginContact contact = n.getContact();
										
										m.put( "k", contact.exportToMap());
									}
								}catch( Throwable e ){
									
									Debug.out( e );
								}
							}else{
								
								Debug.out( "Should always have pk" );
							}
						}
					}
					
					boolean	 is_compressed = false;
					
					if ( caller_version >= 4 ){
					
						Map temp = new HashMap();
						
						temp.put( "m", l );
						
						byte[] plain = BEncoder.encode( temp );
						
						ByteArrayOutputStream	baos = new ByteArrayOutputStream( plain.length * 2 );
						
						GZIPOutputStream zip = new GZIPOutputStream( baos );
						
						zip.write( plain );
						
						zip.finish();
						
						zip.close();
						
						byte[] compressed = baos.toByteArray();
						
						if ( compressed.length < plain.length ){
							
							reply_map.put( "z", compressed );
							
							is_compressed = true;
						}
					}
					
					if ( !is_compressed ){
					
						reply_map.put( "m", l );
					}
				}
				
				int messages_they_should_have = messages_they_have + messages_hidden;
				
				if ( 	messages_they_should_have > messages_we_have ||
						( 	messages_they_should_have == messages_we_have &&
							messages_we_have_they_deleted > 0 )){
					
					Map<String,Object> rendezvous_map = (Map<String,Object>)request_map.get( "z" );

					if ( rendezvous_map != null ){
						
						try{
							DHTPluginContact rendezvous =  dht.importContact( rendezvous_map );
							
							if ( rendezvous != null ){
								
								originator_node.setRendezvous( rendezvous );
							}
						}catch( Throwable e ){
						}
					}
						// previously thought about returning bloom so and have them push messages to us. However,
						// if we can get a reply to them with the bloom then we should equally as well be able to 
						// hit them directly as normal
					
					byte[] bk = originator_node.getContactAddress().getBytes( "UTF-8" );
					
					synchronized( biased_node_bloom ){
												
						if ( biased_node_in == null && !biased_node_bloom.contains( bk )){
							
							biased_node_bloom.add( bk );
							
							if ( TRACE )trace( "Proposing biased node_in " + originator_node.getName() + ", rendezvous=" + rendezvous_map );

							biased_node_in = originator_node;
						}
					}
				}
				
				MsgSyncNode rln = random_liveish_node;
				
				if ( rln != null && rln != originator_node ){
				
					Map rln_map = new HashMap();
					
					rln_map.put( "u", rln.getUID());
					
					rln_map.put( "c", rln.getContact().exportToMap());
					
					reply_map.put( "n", rln_map );
				}
			}
			
			reply_map.put( "s", status );
			
			reply_map.put( "t", RT_SYNC_REPLY );		// type

			if ( more_to_come > 0 ){
				
				reply_map.put( "x", more_to_come );
			}
			
			byte[] reply_data = BEncoder.encode( reply_map );
			
			if ( private_messaging_secret != null ){
				
				reply_data = privateMessageEncrypt( reply_data );
				
			}else{
								
				reply_data = generalMessageEncrypt( reply_data );
			}
			
			return( reply_data );
			
		}catch( Throwable e ){
			
			if ( TRACE )trace( e.toString());
		}
		
		return( null );
	}
	
	@Override
	public byte[]
	handleWrite(
		DHTPluginContact	originator,
		byte[]				call_key,
		byte[]				value )
	{
			// switched to using 'call' to allow larger bloom sizes - in this case we come in
			// here with a unique rcp key for the 'key and the value is the payload
		
		return( handleRead( originator, value ));
	}
	
	private void
	updateProtocolCounts(
		InetSocketAddress	isa )
	{
		if ( isa.isUnresolved()){
			
		}else{
			
			long	v4;
			long	v6;
			
			if ( isa.getAddress() instanceof Inet4Address ){
				
				v4	= v4_count.incrementAndGet();
				
				v6 = v6_count.get();
				
			}else{
				
				v4	= v4_count.get();
				
				v6 = v6_count.incrementAndGet();
			}
			
			if ( SystemTime.getMonotonousTime() - create_time > 2*60*1000 ){
				
				Boolean ipv6_hint = null;
				
				if ( v4 == 0 ){
					
					if ( v6 > 5 ){
						
						ipv6_hint = true;
					}
				}else{
					
					ipv6_hint = false;
				}
				
				if ( ipv6_hint != null ){
					
					if ( my_node.setIPv6Hint( ipv6_hint )){
						
						String	config_key = CryptoManager.CRYPTO_CONFIG_PREFIX + "msgsync." + dht.getNetwork() + "." + ByteFormatter.encodeString( user_key );
						
						Map map = COConfigurationManager.getMapParameter( config_key, new HashMap());
	
						if ( ipv6_hint ){
							
							map.put( "v6hint", true );
							
						}else{
							
							map.remove( "v6hint" );
						}
						
						COConfigurationManager.setParameter( config_key, map );
						
						COConfigurationManager.setDirty();
					}
				}
			}
		}
	}
	
	public void
	addListener(
		MsgSyncListener		listener )
	{
		listeners.add( listener );
		
		if ( !is_private_chat ){
		
			reportInfoText( listener, "azmsgsync.report.joined", friendly_name );
		}
	}
	
	public void
	removeListener(
		MsgSyncListener		listener )
	{
		listeners.remove( listener );
	}
	
	private int
	getUndeliveredMessageCount()
	{
		int	result = 0;
		
		synchronized( message_lock ){
			
			for ( MsgSyncMessage msg: messages ){

				if ( msg.getNode() == my_node ){
					
					if ( msg.getSeenCount() == 0 && msg.getProbablySeenCount() < 5 ){
					
						result++;
					}
				}
			}
		}
		
		return( result );
	}
	
	private void
	loadMessages()
	{
		if ( save_messages ){
		
			File file_name = getMessageFile();

			if ( file_name.exists()){
				
				synchronized( message_lock ){

					if ( dht.isInitialising()){
					
						messages_loading = true;
						
						final TimerEventPeriodic[]	event = {null};
						
						event[0] = 
							SimpleTimer.addPeriodicEvent(
								"msg-load",
								1000,
								new TimerEventPerformer() 
								{
									@Override
									public void 
									perform(
										TimerEvent e ) 
									{
										synchronized( message_lock ){
	
											if ( messages_loading ){
												
												if ( dht.isInitialising()){
		
													return;
												}
												
												messages_loading = false;
													
												boolean was_empty = message_mutation_id == 0;
												
												loadMessages( file_name );
												
												if ( was_empty ){
													
													save_messages_mutation_id = message_mutation_id;
												}
											}
											
											event[0].cancel();
										}
									}
								});
					}else{
						
						loadMessages( file_name );
					}
				}
			}
		}
	}
	
	private void
	loadMessages(
		File		file_name )
	{
		try{
			Map map = FileUtil.readResilientFile( file_name );
			
			Long				time 		= (Long)map.get( "time" );
			Map<String,Map>		node_imp	= (Map)map.get( "nodes" );
			List<Map>			msg_imp		= (List<Map>)map.get( "messages" );
			
			if ( time == null || node_imp == null || msg_imp == null ){
				
				return;
			}
			
			int	elapsed_secs = (int)( ( SystemTime.getCurrentTime() - time )/1000 );
			
			if ( elapsed_secs < 0 ){
				
				elapsed_secs = 0;
			}
			
			Map<Integer,MsgSyncNode>	node_map = new HashMap<Integer, MsgSyncNode>();
			
			for ( Map.Entry<String,Map>	entry: node_imp.entrySet()){
				
				int id = Integer.parseInt( entry.getKey());
				
				Map	m = entry.getValue();
				
				byte[]	uid 		= (byte[])m.get( "u" );
				byte[]	public_key	= (byte[])m.get( "p" );
				
				DHTPluginContact	contact = dht.importContact((Map)m.get( "c" ));
				
				if ( contact != null ){
				
					MsgSyncNode node = addNode( contact, uid, public_key );
				
					node_map.put( id, node );
				}
			}
			
			for ( Map m: msg_imp ){
				
				int node_id = ((Long)m.get( "n" )).intValue();
				
				MsgSyncNode node = node_map.get( node_id );
				
				if ( node == null ){
					
					// can happen with fake I2P DHT
					//Debug.out( "Node not found!" );
					
					continue;
				}
				
				byte[]	id 		= (byte[])m.get( "i" );
				byte[]	content = generalMessageDecrypt((byte[])m.get( "c" ));
				byte[]	control = (byte[])m.get( "$" );
				byte[]	sig		= (byte[])m.get( "s" );
				byte[]	history	= (byte[])m.get( "h" );
				
				int	age_secs = ((Long)m.get( "a" )).intValue();
				
				MsgSyncMessage msg = new MsgSyncMessage( node, id, content, control, sig, age_secs + elapsed_secs, history );

				byte[]	local_msg	= (byte[])m.get( "l" );
				
				if ( local_msg != null ){
					
					try{
						msg.setLocalMessage( new String( local_msg, "UTF-8" ));;
						
					}catch( Throwable e ){
						
					}
				}
				
				addMessage( msg, null, MS_LOADING );
			}
						
		}catch( Throwable e ){
		}
		
		log( "Loaded " + messages.size() + " messages" );
	}
	
	protected void
	saveMessages()
	{
		if ( save_messages ){
									
			synchronized( message_lock ){
			
				if ( messages_loading ){
					
					return;
				}
				
				if ( save_messages_mutation_id == message_mutation_id ){
					
					return;
				}
				
				save_messages_mutation_id = message_mutation_id;
				
				Map map = new HashMap();

				map.put( "time", SystemTime.getCurrentTime());
				
				Map	node_exp = new HashMap();

				map.put( "nodes", node_exp );
				
				List msg_exp =new ArrayList<Map>();
				
				map.put( "messages", msg_exp );
			
				Map<MsgSyncNode,Integer>	node_map = new HashMap<MsgSyncNode, Integer>();
						
				for ( MsgSyncMessage msg: messages ){

					MsgSyncNode node = msg.getNode();
					
					Integer node_id = node_map.get( node );
					
					if ( node_id == null ){
						
						node_id = new Integer( node_map.size());
						
						node_map.put( node, node_id );
						
						Map m = new HashMap();
						
						m.put( "u", node.getUID());
						m.put( "p", node.getPublicKey());
						m.put( "c", node.getContact().exportToMap());
						
						node_exp.put( String.valueOf( node_id ), m );
					}
					
					Map m = new HashMap();
					
					m.put( "n", node_id.intValue());
					m.put( "i", msg.getID());
					m.put( "c", generalMessageEncrypt( msg.getContent())); 
					m.put( "s", msg.getSignature());
					m.put( "a", msg.getAgeSecs());
					m.put( "h", msg.getHistory());
					
					byte[] control = msg.getControl();
					
					if ( control != null ){
						
						m.put("$", control );
					}
					
					String lm = msg.getLocalMessage();
					
					if ( lm != null ){
						
						try{
							map.put("l", lm.getBytes( "UTF-8" ));
							
						}catch( Throwable e ){
							
						}
					}
					
					msg_exp.add( m );
				}
					
				File file_name = getMessageFile();

				FileUtil.writeResilientFile( file_name,  map );
				
				log( "Saved " + messages.size() + " messages" );
			}
		}
	}
	
	private void
	deleteMessages()
	{		
		File file_name = getMessageFile();

		file_name.delete();
	}
	
	private File
	getMessageFile()
	{
		File dir = plugin.getPersistDir();
		
		byte[] key = user_key;
		
		File old_file_name = null;
		
		String suffix =  (is_anonymous_chat?"a":"p") + ".dat";
		
		if ( user_key.length > 64 ){
		
				// migrate from when file names could get pretty long and cause issues on some file systems
			
			old_file_name = new File( dir, Base32.encode( user_key ) + suffix );
			
			key = new SHA1Simple().calculateHash( user_key );
		}
		
		File file_name = new File( dir, Base32.encode( key ) + suffix );
		
		if ( !file_name.exists()){
			
			if ( old_file_name != null && old_file_name.exists()){
				
				old_file_name.renameTo( file_name );
			}
		}
		
		return( file_name );
	}
	
	protected void
	destroy(
		boolean	force_immediate )
	{
		boolean linger = is_private_chat && !force_immediate;
		
		if ( linger ){
			
			linger = getUndeliveredMessageCount() > 0;
		}
		
		if ( linger ){
			
			log( "Destroying..." );
			
			final long start = SystemTime.getMonotonousTime();
			
			final TimerEventPeriodic[] temp = { null };
			
			synchronized( temp ){
				
				temp[0] = 
					SimpleTimer.addPeriodicEvent(
						"mh:linger",
						5*1000,
						new TimerEventPerformer()
						{	
							@Override
							public void 
							perform(
								TimerEvent event) 
							{	
								if ( SystemTime.getMonotonousTime() - start < 60*1000 ){
									
									if ( getUndeliveredMessageCount() > 0 ){
										
										return;
									}
								}
								
								synchronized( temp ){
									
									temp[0].cancel();
								}
								
								destroy( true );
							}
						});
			}
		}else{
			
			log( "Destroyed" );

			destroyed	= true;
			
			status = ST_DESTROYED;
			
			synchronized( pending_handler_regs ){
				
				if ( dht_listen_keys_registered ){
				
					dht.unregisterHandler( dht_listen_key, this );
				
					if ( peek_xfer_handler != null ){
				
						dht.unregisterHandler( peek_xfer_key, peek_xfer_handler ); 
					}
				}else{
					
					pending_handler_regs.clear();
				}
			}
			
			dht.removeListener( this );
		}
	}
	
	private void
	trace(
		String		str )
	{		
		System.out.println( str );
	}
	
	private void
	log(
		String	str )
	{
		String flags = (is_anonymous_chat?"A":"P") + "," + (full_init?"F":"T");
		
		plugin.log( friendly_name + " (" + flags + "): " + str );
	}
	

	protected String
	getString()
	{
		return( dht.getNetwork() + "/" + ByteFormatter.encodeString( dht_listen_key ) + "/" + new String( user_key ));
	}
}
