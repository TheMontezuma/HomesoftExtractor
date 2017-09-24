package org.montezuma.homesoftextractor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;


public class HomesoftExtractor
{
	final static String GAME_TITLES_FILENAME = "GAMES.TXT";
	final static String GAME_SUFFIX = ".xex";
	final static int DD_ATR_SIZE = 183952;
	ArrayList<Disk> mDisks = new ArrayList<Disk>();
	
	class Disk
	{
		final static String ATR_NAME_PREFIX = "GAMES";
		final static String ATR_NAME_SUFFIX = ".ATR";
		Disk(int disk_no)
		{
			mName = String.format("%s%03d%s",ATR_NAME_PREFIX,disk_no,ATR_NAME_SUFFIX);
			mGames = new ArrayList<String>();
		}
		void dump()
		{
			System.out.println(mName);
			Enumeration<String> en = Collections.enumeration(mGames);
			while(en.hasMoreElements())
			{
				String title = en.nextElement();
				System.out.println(title);
			}
		}
		String mName;
		ArrayList<String> mGames;
	}
	
	class AtariFile
	{
		String name;
		byte[] data;
	}
	
	class DDFileSystem
	{
		static final int DD_SECTOR_LENGTH = 256;
		byte[] mData;
		int mFileIndex=0;
		
		DDFileSystem(byte[] data)
		{
			mData = data;
		}
		
		private byte[] getSector(int sector_no)
		{
			byte[] sector_data = new byte[DD_SECTOR_LENGTH];
	    	int pos = (sector_no - 1) * DD_SECTOR_LENGTH - 0x170; // do not care about sectors 1-3
			System.arraycopy(mData, pos, sector_data, 0, DD_SECTOR_LENGTH);
			return sector_data;
		}
		
		int getFilesCount()
		{
			int index = 0;
			int attributes = 0;
			do
			{
				byte[] directory = getSector(0x169+(index>>3));
				int entry = index%8;
				attributes = directory[entry*16] & 0xFF;
				index++;
			} while (index<64 && ((attributes & 0x40) != 0));
			return (index-1);
		}
		
		int getCOMFilesCount()
		{
			int count = 0;
			int index = 0;
			int entry = 0;
			byte[] directory = null;
			while(index<64)
			{
				directory = getSector(0x169+(index>>3));
				entry = index%8;
				index++;
				if( ((directory[entry*16+13] & 0xFF) == 0x43) && ((directory[entry*16+14] & 0xFF) == 0x4F) && ((directory[entry*16+15] & 0xFF) == 0x4D) )
				{
					count++;
				}
			}
			return count;
		}
	
		AtariFile getNextAtariFile(boolean onlyCOM)
		{
			int entry = 0;
			byte[] directory = null;
			boolean COMFileFound = false;
			
			while(mFileIndex<64)
			{
				directory = getSector(0x169+(mFileIndex>>3));
				entry = mFileIndex%8;
				mFileIndex++;
				
				boolean file_exist = (directory[entry*16] & 0x40) == 0x40;
				COMFileFound = ((directory[entry*16+13] & 0xFF) == 0x43) && ((directory[entry*16+14] & 0xFF) == 0x4F) && ((directory[entry*16+15] & 0xFF) == 0x4D);
				if(file_exist && (!onlyCOM || COMFileFound)) break;
			}
			
			if(onlyCOM && !COMFileFound)return null;
				
			int sector_count = (directory[entry*16+1] & 0xFF) + (directory[entry*16+2] & 0xFF) * 0x100;
			int sector_no =    (directory[entry*16+3] & 0xFF) + (directory[entry*16+4] & 0xFF) * 0x100;
			
			String file_name = new String(); 
			
			for (int fnameix=5;fnameix<13;fnameix++)
			{
				char c = (char)(directory[entry*16+fnameix]);
				if(c == ' ')
				{
					break;
				}
				file_name += c;
			}
			if((char)(directory[entry*16+13]) != ' ')
			{
				file_name += '.';
				if(COMFileFound)
				{
					file_name += "XEX";
				}
				else
				{
					for (int fextix=13;fextix<16;fextix++)
					{
						char c = (char)(directory[entry*16+fextix]);
						if(c == ' ')
						{
							break;
						}
						file_name += c;
					}
				}
			}
			
			byte[] tmp = new byte[sector_count*DD_SECTOR_LENGTH];
			int file_length = 0;
			
			for(int i=0; i<sector_count ; i++)
			{
				byte[] sector = getSector(sector_no);
				int length = sector[DD_SECTOR_LENGTH-1] & 0xFF;
				file_length += length;
				System.arraycopy(sector,0,tmp,i*(DD_SECTOR_LENGTH-3),length);
				sector_no = (sector[DD_SECTOR_LENGTH-3] & 0x03) * 0x100 + (sector[DD_SECTOR_LENGTH-2] & 0xFF);
			}
			AtariFile comFile = new AtariFile();
			comFile.name = file_name;
			comFile.data = new byte[file_length];
			System.arraycopy(tmp,0,comFile.data,0,file_length);
			return comFile;
		}
	}
	
	void loadGameList() throws Exception
	{
		BufferedReader br = null;
		try
		{
			File game_list_file = new File(GAME_TITLES_FILENAME);
			br = new BufferedReader(new FileReader(game_list_file));
			String line;
			Disk disk = null;
			while ((line = br.readLine()) != null)
			{
				int tab_index = line.indexOf('\t');
				if(tab_index!=-1)
				{
					if(tab_index!=0)
					{
						try
						{
							int disk_no = Integer.valueOf(line.substring(0, tab_index).trim());
							if(disk!=null)
							{
								mDisks.add(disk);
							}
							disk = new Disk(disk_no);
							disk.mGames.add(line.substring(tab_index+1)+GAME_SUFFIX);
						}
						catch(java.lang.NumberFormatException e){}
					}
					else
					{
						disk.mGames.add(line.substring(tab_index+1)+GAME_SUFFIX);
					}
				}
			}
			if(disk!=null)
			{
				mDisks.add(disk);
			}
			
		}
		finally
		{
			if(br!=null) br.close();
		}
	}
	
	void extract() throws Exception
	{
		Enumeration<Disk> disks = Collections.enumeration(mDisks);
		byte[] atr_data = new byte[DD_ATR_SIZE];
		while(disks.hasMoreElements())
		{
			Disk d = disks.nextElement();
			try
			{
				FileInputStream in = new FileInputStream(d.mName);
				if(DD_ATR_SIZE == in.available())
				{
					in.read(atr_data);
					DDFileSystem fs = new DDFileSystem(atr_data);
					int comFilesCount = fs.getCOMFilesCount();
					int filesCount = fs.getFilesCount();
					if(d.mGames.size()==filesCount && d.mGames.size()==comFilesCount)
					{
						System.out.println(d.mName+" OK");
						Enumeration<String> titles = Collections.enumeration(d.mGames);
						while(titles.hasMoreElements())
						{
							String title = titles.nextElement().replaceAll("[:\\\\/*?|<>]", "_");
							FileOutputStream out = new FileOutputStream(title);
							out.write(fs.getNextAtariFile(true).data);
							out.close();
						}
					}
					else
					{
						System.err.println(d.mName+" NOT OK");
						String NOT_OK_PREFIX = d.mName; 
						for (int ix=0 ; ix<filesCount ; ix++)
						{
							AtariFile comFile = fs.getNextAtariFile(false);
							String fname = "NOT OK"+File.separator+NOT_OK_PREFIX;
							File f = new File(fname);
							f.mkdirs();
							FileOutputStream out = new FileOutputStream(fname+File.separator+comFile.name);
							out.write(comFile.data);
							out.close();
						}
					}
				}
				in.close();
			}
			catch(java.io.FileNotFoundException fex)
			{
				// ignore
			}
			catch(Exception ex)
			{
				throw ex;
			}
		}
	}

	public static void main(String[] args)
	{
		System.out.println("Homesoft Extractor v.1.3");
		try
		{
			HomesoftExtractor hse = new HomesoftExtractor();
			hse.loadGameList();
			hse.extract();
		}
		catch (Exception ex)
		{
			System.err.println("Error: "+ex);
		}
	}

}
