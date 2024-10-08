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

import java.net.Inet6Address;
import java.util.Arrays;

import com.biglybt.core.util.SystemTime;

import com.biglybt.plugin.dht.DHTPluginContact;

public class 
MsgSyncNode 
{
	private DHTPluginContact[]		contacts;
	private byte[]					uid;
	private byte[]					public_key;
	
	private volatile String			contact_str;
	
	private volatile long	last_alive;
	private volatile int	fail_count;
	
	private volatile DHTPluginContact	rendezvous;
	private volatile long				last_tunnel;
	
	private long			last_message_timestamp;
	
	protected
	MsgSyncNode(
		DHTPluginContact		contact,
		byte[]					uid,
		byte[]					public_key )
	{
		this( new DHTPluginContact[]{ contact }, uid, public_key);
	}
	
	protected
	MsgSyncNode(
		DHTPluginContact[]		_contacts,
		byte[]					_uid,
		byte[]					_public_key )
	{
		contacts	= _contacts;
		uid			= _uid;
		public_key	= _public_key;
	}
	
	/**
	 * Just for my-node
	 * @param _contacts
	 */
	
	protected void
	updateContacts(
		DHTPluginContact[]		_contacts )
	{
		
	}
	
		/**
		 * 
		 * @param ipv6_pref
		 * @return true if a change was made
		 */
	
	protected boolean
	setIPv6Hint(
		boolean	ipv6_pref )
	{
		synchronized( this ){
			
			if ( contacts.length < 2 ){
				
				return( false );
			}
		
			boolean	v6_0 = contacts[0].getAddress().getAddress() instanceof Inet6Address;
			boolean	v6_1 = contacts[1].getAddress().getAddress() instanceof Inet6Address;
				
			if ( ipv6_pref ){
				
				if ( v6_0 ){
					
					return( false );
				}
			}else{
				
				if ( !v6_0 ){
					
					return( false );
				}
			}
			
			if ( v6_0 == v6_1 ){
				
				return( false );
			}
					
			DHTPluginContact temp = contacts[0];
			contacts[0] = contacts[1];
			contacts[1] = temp;
				
			contact_str 	= MsgSyncHandler.getString( contacts[0] );
			
			return( true );
		}
	}
		
	protected boolean
	setDetails(
		DHTPluginContact	_contact,
		byte[]				_public_key )
	{
		synchronized( this ){
			
			if ( public_key != null ){
				
				return( Arrays.equals( public_key, _public_key ));
			}
	
			contacts		= new DHTPluginContact[]{ _contact };
			public_key		= _public_key;
			
			return( true );
		}
	}
	
	protected void
	setDetails(
		DHTPluginContact	_contact,
		long				_time )
	{
		synchronized( this ){
			
			contacts		= new DHTPluginContact[]{ _contact };
			
			contact_str 	= MsgSyncHandler.getString( contacts[0] );
			
			last_message_timestamp = _time;
		}
	}
	
	protected long
	getLatestMessageTimestamp()
	{
		synchronized( this ){
			
			return( last_message_timestamp );
		}
	}
	
	protected void
	setRendezvous(
		DHTPluginContact		r )
	{
		rendezvous = r;
	}
	
	protected DHTPluginContact
	getRendezvous()
	{
		return( rendezvous );
	}
	
	protected long
	getLastTunnel()
	{
		return( last_tunnel );
	}
	
	protected void
	setLastTunnel(
		long		t )
	{
		last_tunnel = t;
	}
	
	protected void
	ok()
	{
		last_alive 	= SystemTime.getMonotonousTime();
		fail_count	= 0;
	}
	
	protected long
	getLastAlive()
	{
		return( last_alive );
	}

	protected void
	failed()
	{
		fail_count++;
	}

	protected int
	getFailCount()
	{
		return( fail_count );
	}
	
	public byte[]
	getUID()
	{
		return( uid );
	}
	
	public byte[]
	getPublicKey()
	{
		return( public_key );
	}
	
	public DHTPluginContact
	getContact()
	{
		return( contacts[0] );
	}
	
	public String
	getContactAddress()
	{
		if ( contact_str != null ){
			
			return( contact_str );
		}
		
			//  this can block for a while in the case of anonymous DHT that hasn't initialised, so delay getting it
		
		contact_str = MsgSyncHandler.getString( contacts[0] );
		
		return( contact_str );
	}
	
	public String
	getName()
	{
		return(getContactAddress());
	}
}
