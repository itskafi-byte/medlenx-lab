import { useState, useRef, useEffect } from "react";
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, Legend, ResponsiveContainer,
  PieChart, Pie, Cell, LineChart, Line, AreaChart, Area, LabelList
} from "recharts";

// ═══════════════════════════════════════════════════════════
// DESIGN TOKENS
// ═══════════════════════════════════════════════════════════
const C = {
  brand900: "#0F172A", brand800: "#172554", brand700: "#1E40AF",
  brand600: "#1D4ED8", brand500: "#2563EB", brand400: "#94A3B8",
  brand300: "#CBD5E1", brand200: "#E2E8F0", brand100: "#F1F5F9",
  brand50: "#F8FAFC",
  ok600: "#047857", ok500: "#059669", ok100: "#D1FAE5", ok50: "#ECFDF5",
  warn600: "#B45309", warn500: "#D97706", warn100: "#FEF3C7", warn50: "#FFFBEB",
  orange600: "#EA580C", orangeBg: "#FFF7ED", orangeBorder: "#FDBA74",
  red600: "#DC2626", redBg: "#FEE2E2", redBorder: "#FCA5A5",
  violet600: "#7C3AED", violetBg: "#EDE9FE", violetBorder: "#C4B5FD",
  cyan600: "#0891B2", cyan500: "#0284C7", cyan300: "#38BDF8",
  chart: ["#1E40AF","#059669","#D97706","#0891B2","#7C3AED","#DC2626"],
};

// ═══════════════════════════════════════════════════════════
// GLOBAL COMPONENT LIBRARY
// ═══════════════════════════════════════════════════════════

// 1. Status Pill
const variants: Record<string,{bg:string,text:string,border:string}> = {
  emerald: {bg:"#ECFDF5",text:"#047857",border:"#A7F3D0"},
  amber:   {bg:"#FEF3C7",text:"#B45309",border:"#FDE68A"},
  orange:  {bg:"#FFF7ED",text:"#EA580C",border:"#FDBA74"},
  red:     {bg:"#FEE2E2",text:"#DC2626",border:"#FCA5A5"},
  blue:    {bg:"#EFF6FF",text:"#1D4ED8",border:"#BFDBFE"},
  violet:  {bg:"#EDE9FE",text:"#7C3AED",border:"#C4B5FD"},
  slate:   {bg:"#F1F5F9",text:"#475569",border:"#CBD5E1"},
  dark:    {bg:"#0F172A",text:"#FFFFFF",border:"transparent"},
};

function StatusPill({variant="slate",children,icon,style}:{variant?:string,children:React.ReactNode,icon?:string,style?:React.CSSProperties}) {
  const v = variants[variant] ?? variants.slate;
  return (
    <span style={{display:"inline-flex",alignItems:"center",gap:4,padding:"4px 10px",borderRadius:9999,border:`1px solid ${v.border}`,background:v.bg,color:v.text,fontSize:10,fontWeight:600,lineHeight:1,whiteSpace:"nowrap",...style}}>
      {icon && <span style={{fontSize:9}}>{icon}</span>}{children}
    </span>
  );
}

// 2. Regulatory Micro-Pill
const regStyles: Record<string,{bg:string,text:string,border:string}> = {
  "NEML Listed":          {bg:"#EFF6FF",text:"#1D4ED8",border:"#BFDBFE"},
  "DGDA Price Alert":     {bg:"#FEE2E2",text:"#DC2626",border:"#FCA5A5"},
  "ABX":                  {bg:"#FEF3C7",text:"#B45309",border:"#FDE68A"},
  "ABX ★":                {bg:"#FEE2E2",text:"#DC2626",border:"#FCA5A5"},
  "TRIPS Watch":          {bg:"#FEF3C7",text:"#B45309",border:"#FDE68A"},
  "Duplicate Rx Detected":{bg:"#FEE2E2",text:"#B31D1D",border:"#FCA5A5"},
  "Off-Territory Audit":  {bg:"#FEE2E2",text:"#B31D1D",border:"#FCA5A5"},
  "also Duplicate":       {bg:"#FFFFFF",text:"#DC2626",border:"#FCA5A5"},
};
function RegPill({label,icon}:{label:string,icon?:string}) {
  const s = regStyles[label] ?? regStyles["NEML Listed"];
  return (
    <span style={{display:"inline-flex",alignItems:"center",gap:3,padding:"2px 7px",borderRadius:9999,border:`1px solid ${s.border}`,background:s.bg,color:s.text,fontSize:9,fontWeight:700,whiteSpace:"nowrap"}}>
      {icon && <span style={{fontSize:9}}>{icon}</span>}{label}
    </span>
  );
}

// 3. Confidence Badge
function ConfBadge({pct}:{pct:number}) {
  if (pct >= 85) return <StatusPill variant="emerald">{pct}%</StatusPill>;
  if (pct < 70)  return <StatusPill variant="amber">🏴 {pct}% manual flag</StatusPill>;
  return <StatusPill variant="orange">🤖 {pct}% AI Guess</StatusPill>;
}

// 4. Pharma Company Badge
function CompanyBadge({name,size=20}:{name:string,size?:number}) {
  const initials = name.split(" ").map(w=>w[0]).join("").slice(0,2).toUpperCase();
  return (
    <span style={{display:"inline-flex",alignItems:"center",justifyContent:"center",width:size,height:size,borderRadius:6,background:"#0284C7",color:"#fff",fontSize:size*0.45,fontWeight:700}}>{initials}</span>
  );
}

// 5. Verified Company Pill
function CompanyPill({name,verified,style}:{name:string,verified?:"verified"|"unverified"|"none",style?:React.CSSProperties}) {
  if (!name || verified==="none") return (
    <span style={{display:"inline-flex",alignItems:"center",gap:4,padding:"3px 8px",borderRadius:9999,background:"#E2E8F0",color:"#475569",fontSize:10,fontWeight:600,...style}}>🏢 Company not identified</span>
  );
  if (verified==="unverified") return (
    <span style={{display:"inline-flex",alignItems:"center",gap:4,padding:"3px 8px",borderRadius:9999,background:"linear-gradient(90deg,#F59E0B,#F97316)",color:"#fff",fontSize:10,fontWeight:600,...style}}>⚠ {name}</span>
  );
  return (
    <span style={{display:"inline-flex",alignItems:"center",gap:4,padding:"3px 8px",borderRadius:9999,background:"#2563EB",color:"#fff",fontSize:10,fontWeight:600,...style}}>
      <CompanyBadge name={name} size={16}/>{name} ✓
    </span>
  );
}

// 6. KPI Card
function KPICard({label,value,delta,deltaUp,icon,today,week,month,track,iconBg}:{label:string,value:string,delta?:string,deltaUp?:boolean,icon:string,today?:string,week?:string,month?:string,track?:number,iconBg?:string}) {
  return (
    <div style={{background:"#fff",border:"1px solid #E2E8F0",borderRadius:12,padding:16,position:"relative",minHeight:100}}>
      <div style={{position:"absolute",top:14,right:14,width:40,height:40,borderRadius:12,background:iconBg??`${C.brand50}`,display:"flex",alignItems:"center",justifyContent:"center",fontSize:18}}>{icon}</div>
      <div style={{fontSize:11,fontWeight:500,color:"#64748B",marginBottom:6,paddingRight:48}}>{label}</div>
      <div style={{fontSize:24,fontWeight:700,color:"#0F172A",marginBottom:4}}>{value}</div>
      {delta && (
        <span style={{display:"inline-flex",alignItems:"center",gap:2,padding:"2px 6px",borderRadius:9999,background:deltaUp?"#ECFDF5":"#FEE2E2",color:deltaUp?"#047857":"#DC2626",fontSize:10,fontWeight:600}}>
          {deltaUp?"▲":"▼"} {delta}
        </span>
      )}
      {(today||week||month) && (
        <div style={{display:"flex",gap:4,marginTop:6,flexWrap:"wrap"}}>
          {today && <StatusPill variant="slate">Today {today}</StatusPill>}
          {week && <StatusPill variant="slate">Week {week}</StatusPill>}
          {month && <StatusPill variant="blue">Month {month}</StatusPill>}
        </div>
      )}
      {track!==undefined && (
        <div style={{marginTop:8}}>
          <div className="progress-track"><div className="progress-fill" style={{width:`${track}%`,background:track>=100?"#059669":track>=50?"#D97706":"#DC2626"}}/></div>
        </div>
      )}
    </div>
  );
}

// 7. Mini KPI Tile
function MiniKPI({label,value,dark}:{label:string,value:string,dark?:boolean}) {
  return (
    <div style={{borderRadius:12,padding:12,background:dark?"rgba(255,255,255,0.10)":"#fff",border:dark?"none":"1px solid #E2E8F0"}}>
      <div style={{fontSize:10,color:dark?"rgba(255,255,255,0.70)":"#64748B",fontWeight:500,marginBottom:4}}>{label}</div>
      <div style={{fontSize:20,fontWeight:700,color:dark?"#fff":"#0F172A"}}>{value}</div>
    </div>
  );
}

// 8. Filter Chip
function FilterChip({label,active,onClick}:{label:string,active?:boolean,onClick?:()=>void}) {
  return (
    <button onClick={onClick} style={{padding:"6px 14px",borderRadius:9999,border:active?"none":"1px solid #E2E8F0",background:active?"#0F172A":"#fff",color:active?"#fff":"#475569",fontSize:12,fontWeight:600,cursor:"pointer",whiteSpace:"nowrap"}}>
      {label}
    </button>
  );
}

// 12. Primary Button
function PrimaryBtn({children,onClick,icon,style}:{children:React.ReactNode,onClick?:()=>void,icon?:string,style?:React.CSSProperties}) {
  return (
    <button onClick={onClick} style={{display:"inline-flex",alignItems:"center",gap:6,padding:"10px 16px",borderRadius:12,background:"#0F172A",color:"#fff",fontSize:14,fontWeight:500,border:"none",cursor:"pointer",...style}}>
      {icon&&<span>{icon}</span>}{children}
    </button>
  );
}

// 13. Success Button
function SuccessBtn({children,onClick,icon,style}:{children:React.ReactNode,onClick?:()=>void,icon?:string,style?:React.CSSProperties}) {
  return (
    <button onClick={onClick} style={{display:"inline-flex",alignItems:"center",gap:6,padding:"10px 16px",borderRadius:12,background:"#059669",color:"#fff",fontSize:14,fontWeight:500,border:"none",cursor:"pointer",...style}}>
      {icon&&<span>{icon}</span>}{children}
    </button>
  );
}

// 14. Ghost Button
function GhostBtn({children,onClick,icon,style}:{children:React.ReactNode,onClick?:()=>void,icon?:string,style?:React.CSSProperties}) {
  return (
    <button onClick={onClick} style={{display:"inline-flex",alignItems:"center",gap:6,padding:"10px 16px",borderRadius:12,background:"#fff",color:"#0F172A",fontSize:14,fontWeight:500,border:"1px solid #E2E8F0",cursor:"pointer",...style}}>
      {icon&&<span>{icon}</span>}{children}
    </button>
  );
}

// 16. Refresh Pill
function RefreshPill({onClick}:{onClick?:()=>void}) {
  return (
    <button onClick={onClick} style={{display:"inline-flex",alignItems:"center",gap:4,padding:"4px 10px",borderRadius:9999,background:"#F1F5F9",color:"#475569",fontSize:11,fontWeight:500,border:"none",cursor:"pointer"}}>
      🔄 Refresh
    </button>
  );
}

// 20. Skeleton Row
function SkeletonRow({cyan}:{cyan?:boolean}) {
  return (
    <div style={{borderRadius:12,padding:16,background:"#F1F5F9",borderLeft:cyan?"4px solid #0891B2":"none",marginBottom:8,position:"relative",overflow:"hidden"}}>
      <div style={{width:"60%",height:12,background:"#E2E8F0",borderRadius:6,marginBottom:8}}/>
      <div style={{width:"40%",height:10,background:"#E2E8F0",borderRadius:6}}/>
      <div style={{position:"absolute",inset:0,background:"linear-gradient(90deg,transparent,rgba(6,182,212,0.18),transparent)",animation:"shimmer 1.5s infinite"}}/>
    </div>
  );
}

// 22. Toast
function Toast({variant,message}:{variant:"loading"|"success"|"error"|"info",message:string}) {
  const styles: Record<string,{border:string,icon:string,iconColor:string}> = {
    loading:{border:"#BFDBFE",icon:"⏳",iconColor:"#2563EB"},
    success:{border:"#A7F3D0",icon:"✓",iconColor:"#047857"},
    error:  {border:"#FCA5A5",icon:"✕",iconColor:"#DC2626"},
    info:   {border:"#BFDBFE",icon:"ℹ",iconColor:"#2563EB"},
  };
  const s = styles[variant];
  return (
    <div style={{background:"#fff",border:`1px solid ${s.border}`,borderRadius:12,padding:"12px 16px",display:"flex",alignItems:"center",gap:12,minWidth:280,maxWidth:380,boxShadow:"0 4px 12px rgba(0,0,0,0.08)"}}>
      <span style={{color:s.iconColor,fontSize:16,fontWeight:700}}>{s.icon}</span>
      <span style={{fontSize:14,fontWeight:500,color:"#0F172A"}}>{message}</span>
    </div>
  );
}

// 23. Empty State
function EmptyState({icon,text,cta,onCta}:{icon:string,text:string,cta?:string,onCta?:()=>void}) {
  return (
    <div style={{display:"flex",flexDirection:"column",alignItems:"center",justifyContent:"center",padding:"32px 16px",gap:12,textAlign:"center"}}>
      <div style={{width:48,height:48,borderRadius:9999,background:"#F1F5F9",display:"flex",alignItems:"center",justifyContent:"center",fontSize:22,color:"#94A3B8"}}>{icon}</div>
      <div style={{fontSize:12,color:"#64748B",maxWidth:260}}>{text}</div>
      {cta && <button onClick={onCta} style={{padding:"8px 18px",borderRadius:9999,background:"#0F172A",color:"#fff",fontSize:12,fontWeight:600,border:"none",cursor:"pointer"}}>{cta}</button>}
    </div>
  );
}

// Section Header
function SectionHeader({icon,title,sub}:{icon:string,title:string,sub?:string}) {
  return (
    <div style={{marginBottom:12}}>
      <div style={{display:"flex",alignItems:"center",gap:6,marginBottom:sub?4:0}}>
        <span style={{color:"#1D4ED8",fontSize:14}}>{icon}</span>
        <span style={{fontSize:14,fontWeight:600,color:"#0F172A"}}>{title}</span>
      </div>
      {sub && <div style={{fontSize:11,color:"#64748B"}}>{sub}</div>}
    </div>
  );
}

// Section Label (uppercase small caps)
function SectionLabel({children}:{children:React.ReactNode}) {
  return <div style={{fontSize:12,fontWeight:600,color:"#64748B",letterSpacing:"0.08em",textTransform:"uppercase",marginBottom:8}}>{children}</div>;
}

// Progress Bar
function ProgressBar({pct,thick}:{pct:number,thick?:boolean}) {
  const h = thick?8:6;
  const color = pct>=100?"#059669":pct>=50?"#D97706":"#DC2626";
  return (
    <div style={{height:h,background:"#E2E8F0",borderRadius:9999,overflow:"hidden"}}>
      <div style={{height:"100%",width:`${Math.min(pct,100)}%`,background:color,borderRadius:9999}}/>
    </div>
  );
}

// Dark Hero Panel
function DarkHero({children,style}:{children:React.ReactNode,style?:React.CSSProperties}) {
  return (
    <div style={{background:"linear-gradient(135deg,#0F172A,#1E40AF)",borderRadius:16,padding:24,color:"#fff",position:"relative",overflow:"hidden",...style}}>
      <div style={{position:"absolute",top:-80,right:-80,width:256,height:256,borderRadius:9999,background:"rgba(255,255,255,0.10)",filter:"blur(40px)",pointerEvents:"none"}}/>
      {children}
    </div>
  );
}

// Card Wrapper
function Card({children,style,onClick}:{children:React.ReactNode,style?:React.CSSProperties,onClick?:()=>void}) {
  return (
    <div onClick={onClick} style={{background:"#fff",border:"1px solid #E2E8F0",borderRadius:12,padding:16,...style,cursor:onClick?"pointer":undefined}}>
      {children}
    </div>
  );
}

// ═══════════════════════════════════════════════════════════
// ICON / GLYPH HELPERS (emoji-based for web rendering)
// ═══════════════════════════════════════════════════════════
const Icon = {
  scan:"📋", analytics:"📈", hub:"⚗️", team:"🏢", settings:"⚙️",
  search:"🔍", user:"👤", cloud:"☁️", building:"🏛", wifi:"📶",
  microscope:"🔬", camera:"📷", upload:"⬆", check:"✓", x:"✕",
  flask:"🧪", chartLine:"📊", sitemap:"🗂", cog:"⚙",
  filter:"⚡", csv:"📄", refresh:"🔄", gps:"📍", map:"🗺",
  doctor:"👨‍⚕️", pills:"💊", bacterium:"🦠", target:"🎯",
  fire:"🔥", globe:"🌐", news:"📰", jobs:"💼", calendar:"📅",
  bolt:"⚡", robot:"🤖", flag:"🏴", house:"🏠", bullhorn:"📢",
  ban:"🚫", warning:"⚠", star:"★", book:"📖", pdf:"📑",
  clipboard:"📋", database:"🗄", chartPie:"🥧", userDoctor:"👨‍⚕️",
  fileMedical:"📋", fileCSV:"📄", route:"🛣", trash:"🗑",
  chevronRight:"›", chevronLeft:"‹", chevronUp:"^", chevronDown:"v",
  locationDot:"📍", crosshairs:"🎯", plane:"✈",
  plus:"＋", minus:"－", info:"ℹ",
};

// ═══════════════════════════════════════════════════════════
// TOP APP BAR
// ═══════════════════════════════════════════════════════════
function AppBar({onSearchFocus,showSearch,setShowSearch}:{onSearchFocus?:()=>void,showSearch?:boolean,setShowSearch?:(v:boolean)=>void}) {
  return (
    <div style={{position:"fixed",top:0,left:0,right:0,height:64,background:"rgba(255,255,255,0.92)",backdropFilter:"blur(12px)",borderBottom:"1px solid #E2E8F0",zIndex:30,display:"flex",alignItems:"center",padding:"0 16px",gap:8}}>
      {/* Logo */}
      <div style={{display:"flex",alignItems:"center",gap:8,flexShrink:0}}>
        <div style={{width:32,height:32,borderRadius:8,background:"linear-gradient(135deg,#0F172A,#1E40AF)",display:"flex",alignItems:"center",justifyContent:"center",fontSize:14}}>🔬</div>
        <span style={{fontSize:14,fontWeight:700,color:"#0F172A",letterSpacing:"-0.02em"}}>MedLenX</span>
      </div>
      {/* Search */}
      <div style={{flex:1,position:"relative"}}>
        <input
          onFocus={()=>setShowSearch&&setShowSearch(true)}
          placeholder="Search medicines, doctors, generics - always accessible from anywhere..."
          style={{width:"100%",height:36,borderRadius:12,border:`1px solid ${showSearch?"#2563EB":"#E2E8F0"}`,padding:"0 12px 0 32px",fontSize:11,outline:"none",background:"#fff",boxShadow:showSearch?"0 0 0 2px rgba(37,99,235,0.20)":"none",fontFamily:"Inter,sans-serif"}}
        />
        <span style={{position:"absolute",left:10,top:"50%",transform:"translateY(-50%)",fontSize:12,color:"#94A3B8"}}>🔍</span>
      </div>
      {/* Status Chips */}
      <div style={{display:"flex",alignItems:"center",gap:4,flexShrink:0}}>
        <StatusPill variant="emerald"><span style={{display:"inline-block",width:6,height:6,borderRadius:"50%",background:"#047857",marginRight:3}}/>Online</StatusPill>
        <StatusPill variant="dark">☁ 0 queued</StatusPill>
        <StatusPill variant="dark">🏛 Set company</StatusPill>
        <div style={{width:32,height:32,borderRadius:9999,background:"#0F172A",display:"flex",alignItems:"center",justifyContent:"center",fontSize:13}}>👤</div>
      </div>
    </div>
  );
}

// Global Search Overlay
function SearchOverlay({onClose}:{onClose:()=>void}) {
  const results = [
    {brand:"Napa",meta:"Tablet 500 mg • Square Pharmaceuticals Ltd.",form:"Tablet"},
    {brand:"Seclo",meta:"Capsule 20 mg • Square Pharmaceuticals Ltd.",form:"Capsule"},
    {brand:"Amdocal",meta:"Tablet 5 mg • ACI Limited",form:"Tablet"},
    {brand:"Opal",meta:"Capsule 20 mg • Healthcare Pharmaceuticals Ltd.",form:"Capsule"},
    {brand:"Azithro",meta:"Tablet 500 mg • Beximco Pharmaceuticals Ltd.",form:"Tablet"},
    {brand:"Cefixol",meta:"Capsule 200 mg • Renata Limited",form:"Capsule"},
  ];
  return (
    <div onClick={onClose} style={{position:"fixed",inset:0,zIndex:35,paddingTop:64}}>
      <div onClick={e=>e.stopPropagation()} style={{background:"#fff",borderRadius:12,border:"1px solid #E2E8F0",boxShadow:"0 8px 32px rgba(0,0,0,0.12)",margin:"4px 16px",maxHeight:320,overflow:"auto"}}>
        {results.map((r,i)=>(
          <div key={i} style={{display:"flex",alignItems:"center",gap:10,padding:"10px 12px",borderBottom:i<results.length-1?"1px solid #F1F5F9":"none",background:i===2?"#F8FAFC":"#fff",cursor:"pointer"}}>
            <div style={{width:40,height:40,borderRadius:8,background:"#F8FAFC",border:"1px solid #E2E8F0",display:"flex",alignItems:"center",justifyContent:"center",flexShrink:0,fontSize:18}}>💊</div>
            <div style={{flex:1,minWidth:0}}>
              <div style={{fontSize:12,fontWeight:600,color:"#0F172A"}}>{r.brand}</div>
              <div style={{fontSize:10,color:"#64748B",overflow:"hidden",textOverflow:"ellipsis",whiteSpace:"nowrap"}}>{r.meta}</div>
            </div>
            <StatusPill variant="blue">{r.form}</StatusPill>
          </div>
        ))}
      </div>
    </div>
  );
}

// ═══════════════════════════════════════════════════════════
// BOTTOM NAVIGATION
// ═══════════════════════════════════════════════════════════
const navItems = [
  {id:"scan",icon:"📋",label:"Scan"},
  {id:"analytics",icon:"📈",label:"Analytics"},
  {id:"hub",icon:"⚗️",label:"Hub"},
  {id:"team",icon:"🗂",label:"Team"},
  {id:"settings",icon:"⚙️",label:"Settings"},
];

function BottomNav({active,onChange}:{active:string,onChange:(id:string)=>void}) {
  return (
    <div style={{position:"fixed",bottom:0,left:0,right:0,height:60,background:"#fff",borderTop:"1px solid #E2E8F0",display:"flex",alignItems:"center",zIndex:30}}>
      {navItems.map(item=>{
        const isActive = item.id===active;
        return (
          <button key={item.id} onClick={()=>onChange(item.id)}
            style={{flex:1,display:"flex",flexDirection:"column",alignItems:"center",justifyContent:"center",gap:2,border:"none",background:"transparent",cursor:"pointer",padding:0}}>
            {isActive ? (
              <div style={{display:"flex",flexDirection:"column",alignItems:"center",gap:2,background:"#0F172A",borderRadius:9999,padding:"6px 16px"}}>
                <span style={{fontSize:14,color:"#fff"}}>{item.icon}</span>
                <span style={{fontSize:10,fontWeight:600,color:"#fff"}}>{item.label}</span>
              </div>
            ) : (
              <>
                <span style={{fontSize:16,color:"#64748B"}}>{item.icon}</span>
                <span style={{fontSize:10,fontWeight:500,color:"#64748B"}}>{item.label}</span>
              </>
            )}
          </button>
        );
      })}
    </div>
  );
}

// Content area wrapper
function ContentArea({children}:{children:React.ReactNode}) {
  return (
    <div style={{paddingTop:72,paddingBottom:80,paddingLeft:16,paddingRight:16,minHeight:"100vh",background:"#F8FAFC"}}>
      {children}
    </div>
  );
}

// ═══════════════════════════════════════════════════════════
// SCAN SCREENS
// ═══════════════════════════════════════════════════════════
function ScanEmpty({onCamera,onFile}:{onCamera:()=>void,onFile:()=>void}) {
  return (
    <div>
      {/* Offline Banner */}
      <div style={{background:"#FFFBEB",border:"1px solid #FDE68A",borderRadius:8,padding:"8px 12px",marginBottom:16,fontSize:11,color:"#B45309",display:"flex",gap:6,alignItems:"center"}}>
        ⚠ Offline-first — scans cache on device when the rural network drops, then sync on reconnect.
      </div>
      {/* Upload Hero */}
      <div style={{background:"#fff",borderRadius:16,border:"2px dashed #BFDBFE",padding:"40px 24px",display:"flex",flexDirection:"column",alignItems:"center",textAlign:"center",gap:16}}>
        <div style={{width:80,height:80,borderRadius:16,background:"linear-gradient(135deg,#0F172A,#1E40AF)",display:"flex",alignItems:"center",justifyContent:"center",fontSize:32,boxShadow:"0 8px 24px rgba(15,23,42,0.3)"}}>📋</div>
        <div>
          <div style={{fontSize:20,fontWeight:700,color:"#0F172A",marginBottom:8}}>Drop prescription image here</div>
          <div style={{fontSize:13,color:"#64748B",maxWidth:320}}>Drag & drop or click to upload. Supports camera capture, rotation, contrast adjustment for cursive handwriting.</div>
        </div>
        <div style={{display:"flex",gap:16,width:"100%",maxWidth:280}}>
          <PrimaryBtn onClick={onFile} icon="⬆" style={{flex:1,justifyContent:"center"}}>Choose File</PrimaryBtn>
          <GhostBtn onClick={onCamera} icon="📷" style={{flex:1,justifyContent:"center"}}>Open Camera</GhostBtn>
        </div>
      </div>
      <div style={{marginTop:16,fontSize:11,color:"#64748B",textAlign:"center"}}>
        Offline-first — scans cache on-device when the rural network drops, then sync on reconnect.
      </div>

      {/* Recent Prescriptions */}
      <div style={{marginTop:24}}>
        <RecentPrescriptions />
      </div>
    </div>
  );
}

function ScanCamera({onCapture,onBack}:{onCapture:()=>void,onBack:()=>void}) {
  return (
    <div style={{position:"fixed",inset:0,background:"#000",zIndex:50,display:"flex",flexDirection:"column"}}>
      {/* Viewfinder */}
      <div style={{flex:1,position:"relative",display:"flex",alignItems:"center",justifyContent:"center"}}>
        {/* Prescription pad */}
        <div style={{width:"85%",height:"65%",position:"relative",display:"flex",alignItems:"center",justifyContent:"center"}}>
          {/* Corner brackets */}
          <div style={{position:"absolute",top:0,left:0,width:28,height:28}} className="scan-bracket-tl"/>
          <div style={{position:"absolute",top:0,right:0,width:28,height:28}} className="scan-bracket-tr"/>
          <div style={{position:"absolute",bottom:0,left:0,width:28,height:28}} className="scan-bracket-bl"/>
          <div style={{position:"absolute",bottom:0,right:0,width:28,height:28}} className="scan-bracket-br"/>
          <div style={{fontSize:40,opacity:0.3}}>📋</div>
        </div>
        <div style={{position:"absolute",top:16,left:"50%",transform:"translateX(-50%)",background:"rgba(0,0,0,0.5)",borderRadius:9999,padding:"6px 14px",fontSize:11,color:"#fff",whiteSpace:"nowrap"}}>Hold still for 1 second</div>
        {/* Mode chips */}
        <div style={{position:"absolute",bottom:100,left:"50%",transform:"translateX(-50%)",display:"flex",gap:8}}>
          {["Portrait","Landscape","Flash off"].map(l=>(
            <div key={l} style={{padding:"4px 12px",borderRadius:9999,background:"rgba(0,0,0,0.5)",color:"#fff",fontSize:11,border:"1px solid rgba(255,255,255,0.3)"}}>{l}</div>
          ))}
        </div>
      </div>
      {/* Controls */}
      <div style={{background:"#0F172A",padding:"20px 24px",display:"flex",alignItems:"center",justifyContent:"space-between",paddingBottom:36}}>
        <button style={{width:44,height:44,borderRadius:8,border:"1px solid #334155",background:"transparent",color:"#fff",fontSize:18,cursor:"pointer"}} onClick={onBack}>🖼</button>
        <button onClick={onCapture} style={{width:72,height:72,borderRadius:9999,background:"#fff",border:"4px solid #F8FAFC",cursor:"pointer",display:"flex",alignItems:"center",justifyContent:"center",fontSize:24}}>📸</button>
        <button style={{width:44,height:44,borderRadius:8,border:"1px solid #334155",background:"transparent",color:"#fff",fontSize:18,cursor:"pointer"}}>🔦</button>
      </div>
      <div style={{background:"#0F172A",textAlign:"center",paddingBottom:16,fontSize:10,color:"#94A3B8"}}>
        Tap Open Camera (not Choose File) on phones. Hold still 1s. Review confidence badges.
      </div>
    </div>
  );
}

function ScanInProgress({onDone}:{onDone:()=>void}) {
  const [progress, setProgress] = useState(40);
  useEffect(()=>{
    const t = setTimeout(()=>{setProgress(75);},1000);
    const t2 = setTimeout(()=>{setProgress(100);onDone();},3000);
    return ()=>{clearTimeout(t);clearTimeout(t2);};
  },[onDone]);

  return (
    <div>
      {/* Prescription image with scan laser */}
      <div style={{background:"#fff",borderRadius:16,overflow:"hidden",marginBottom:16,position:"relative",height:220}}>
        <div style={{width:"100%",height:"100%",background:"linear-gradient(180deg,rgba(8,145,178,0.08),transparent)",display:"flex",alignItems:"center",justifyContent:"center",fontSize:60,opacity:0.3}}>📋</div>
        <div className="scan-laser"/>
        <div style={{position:"absolute",top:12,right:12,background:"rgba(8,145,178,0.9)",borderRadius:9999,padding:"4px 10px",fontSize:10,fontWeight:700,color:"#fff",display:"flex",alignItems:"center",gap:4}}>
          <span style={{width:6,height:6,borderRadius:"50%",background:"#fff",display:"inline-block",animation:"pulse 1s infinite"}}/>Scanning…
        </div>
      </div>
      {/* Status card */}
      <Card style={{marginBottom:12}}>
        <div style={{display:"flex",alignItems:"center",gap:10,marginBottom:12}}>
          <div style={{width:16,height:16,borderRadius:"50%",border:"2px solid #2563EB",borderTopColor:"transparent",animation:"spin 0.8s linear infinite"}}/>
          <span style={{fontSize:12,color:"#475569"}}>Extracting with MedLenX VL...</span>
        </div>
        <ProgressBar pct={progress}/>
      </Card>
      {/* Skeletons */}
      <SkeletonRow cyan/>
      <SkeletonRow cyan/>
      <SkeletonRow cyan/>
      <SkeletonRow cyan/>
    </div>
  );
}

// Medicine Card (Frame 10)
function MedicineCardComponent({brand,pct,type,ingredient,strength,dosage,company,verified,catalogueNote,aiGuess,manualFlag,variant}:{
  brand:string,pct:number,type:string,ingredient:string,strength:string,dosage:string,
  company:string,verified?:"verified"|"unverified"|"none",catalogueNote?:string,
  aiGuess?:boolean,manualFlag?:boolean,variant?:string
}) {
  const borderColor = aiGuess?"#FDBA74":manualFlag?"#FDE68A":"#E2E8F0";
  const bgColor = aiGuess?"#FFF7ED":manualFlag?"#FFFBEB":"#fff";
  return (
    <div style={{background:bgColor,border:`1px solid ${borderColor}`,borderRadius:12,padding:12,marginBottom:12}}>
      <div style={{display:"flex",gap:10,marginBottom:10}}>
        {/* Pack image */}
        <div style={{width:64,height:64,borderRadius:10,background:"#F8FAFC",border:"2px solid #F1F5F9",flexShrink:0,position:"relative",display:"flex",alignItems:"center",justifyContent:"center",fontSize:28}}>
          💊
          <div style={{position:"absolute",bottom:-6,right:-6,width:20,height:20,borderRadius:"50%",background:"#fff",border:"1px solid #E2E8F0",display:"flex",alignItems:"center",justifyContent:"center",fontSize:9}}>🖼</div>
        </div>
        <div style={{flex:1,minWidth:0}}>
          <div style={{display:"flex",alignItems:"center",gap:6,marginBottom:4}}>
            <input defaultValue={brand} style={{flex:1,fontSize:15,fontWeight:700,border:"none",borderBottom:"2px solid #E2E8F0",background:"transparent",color:"#0F172A",fontFamily:"Inter,sans-serif",outline:"none",padding:"2px 0"}}/>
            <ConfBadge pct={pct}/>
          </div>
          {/* 2×2 fact tiles */}
          <div style={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:4}}>
            <div style={{background:"#EFF6FF",border:"1px solid #DBEAFE",borderRadius:10,padding:"6px 8px"}}>
              <div style={{fontSize:9,fontWeight:700,color:"#2563EB",textTransform:"uppercase",letterSpacing:"0.08em",marginBottom:2}}>Type</div>
              <div style={{fontSize:11,color:"#0F172A",display:"flex",alignItems:"center",gap:3}}>💊 {type}</div>
            </div>
            <div style={{background:"#EFF6FF",border:"1px solid #DBEAFE",borderRadius:10,padding:"6px 8px"}}>
              <div style={{fontSize:9,fontWeight:700,color:"#1E40AF",textTransform:"uppercase",letterSpacing:"0.08em",marginBottom:2}}>Ingredient</div>
              <div style={{fontSize:11,color:"#0F172A"}}>{ingredient}</div>
            </div>
            <div style={{background:"#ECFDF5",border:"1px solid #D1FAE5",borderRadius:10,padding:"6px 8px"}}>
              <div style={{fontSize:9,fontWeight:700,color:"#059669",textTransform:"uppercase",letterSpacing:"0.08em",marginBottom:2}}>MG/Strength</div>
              <div style={{fontSize:13,fontWeight:700,color:"#0F172A"}}>{strength}</div>
            </div>
            <div style={{background:"#FFFBEB",border:"1px solid #FEF3C7",borderRadius:10,padding:"6px 8px"}}>
              <div style={{fontSize:9,fontWeight:700,color:"#D97706",textTransform:"uppercase",letterSpacing:"0.08em",marginBottom:2}}>Dosage</div>
              <input defaultValue={dosage} placeholder="1+0+1" style={{fontSize:11,border:"none",background:"transparent",width:"100%",color:"#0F172A",fontFamily:"Inter,sans-serif",outline:"none"}}/>
            </div>
          </div>
        </div>
      </div>
      {/* Company badge row */}
      <div style={{marginBottom:8}}>
        <CompanyPill name={company} verified={verified}/>
      </div>
      {/* AI Guess strip */}
      {aiGuess && (
        <div style={{background:"#FFF7ED",border:"1px solid #FDBA74",borderRadius:8,padding:"6px 10px",marginBottom:8,fontSize:11,color:"#EA580C",display:"flex",alignItems:"center",gap:6}}>
          🤖 AI Guess — <span style={{textDecoration:"underline dotted",cursor:"pointer"}}>Tap to verify against Medex</span>
        </div>
      )}
      {/* Catalogue override note */}
      {catalogueNote && (
        <div style={{background:"#FFFBEB",border:"1px solid #FEF3C7",borderRadius:8,padding:"6px 10px",marginBottom:8,fontSize:11,color:"#B45309"}}>
          📋 {catalogueNote}
        </div>
      )}
      {/* Footer */}
      <div style={{display:"flex",justifyContent:"space-between",alignItems:"center"}}>
        <div style={{fontSize:10,color:"#94A3B8"}}>Raw: "{brand.toLowerCase()}…" • MedEx exact match • #L2</div>
        <button style={{fontSize:10,color:"#B45309",background:"none",border:"none",cursor:"pointer",textDecoration:"underline"}}>Report mis-ID</button>
      </div>
    </div>
  );
}

// Verify flow – Doctor info panel
function VerifyDoctor({onNext,onBack}:{onNext:()=>void,onBack:()=>void}) {
  return (
    <div style={{background:"#fff",borderRadius:16,overflow:"hidden"}}>
      {/* Prescription viewer thumbnail */}
      <div style={{height:180,background:"#F1F5F9",position:"relative",display:"flex",alignItems:"center",justifyContent:"center"}}>
        <div style={{fontSize:48,opacity:0.4}}>📋</div>
        {/* Amber bbox */}
        <div style={{position:"absolute",left:"30%",top:"40%",right:"30%",bottom:"30%",border:"2px solid #FB923C",borderRadius:4}}/>
        <div style={{position:"absolute",right:12,bottom:12}}>
          <div style={{display:"flex",gap:4}}>
            {["🔍+","🔍−","↺","↻","◑","⊡"].map((ic,i)=>(
              <button key={i} style={{width:28,height:28,borderRadius:8,border:"1px solid #E2E8F0",background:"#fff",cursor:"pointer",fontSize:11,display:"flex",alignItems:"center",justifyContent:"center"}}>{ic}</button>
            ))}
          </div>
        </div>
        <div style={{position:"absolute",bottom:12,left:12,background:"rgba(15,23,42,0.7)",borderRadius:9999,padding:"3px 8px",fontSize:10,color:"#fff"}}>
          Pinch to zoom · Drag to pan · 100%
        </div>
      </div>

      <div style={{padding:16}}>
        {/* Header */}
        <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:16}}>
          <span style={{fontSize:13,color:"#059669"}}>✓</span>
          <span style={{fontSize:14,fontWeight:600,color:"#0F172A"}}>Data Verification</span>
          <StatusPill variant="amber">⏳ Pending Verification</StatusPill>
          <div style={{marginLeft:"auto",width:32,height:32,borderRadius:9999,border:"1px solid #E2E8F0",display:"flex",alignItems:"center",justifyContent:"center",cursor:"pointer"}}>✕</div>
        </div>

        <SectionLabel>Doctor Information — BMDC Verification</SectionLabel>

        {/* Doctor fields */}
        {[
          {label:"Doctor Name",value:"Dr. A. K. M. Rahman",conf:96},
          {label:"BMDC Reg No",value:"A-12345",conf:92},
          {label:"Qualifications",value:"MBBS, FCPS (Medicine)",conf:88},
          {label:"Hospital/Chamber",value:"Ibn Sina Hospital, Dhanmondi",conf:85},
        ].map(f=>(
          <div key={f.label} style={{marginBottom:10}}>
            <div style={{fontSize:10,fontWeight:700,color:"#64748B",textTransform:"uppercase",letterSpacing:"0.08em",marginBottom:4}}>{f.label}</div>
            <div style={{position:"relative"}}>
              <input defaultValue={f.value} style={{width:"100%",height:40,borderRadius:12,border:`1px solid ${f.conf>=85?"#E2E8F0":"#FDE68A"}`,background:f.conf>=85?"#fff":"#FFFBEB",padding:"0 36px 0 12px",fontSize:13,fontFamily:"Inter,sans-serif",boxSizing:"border-box",outline:"none"}}/>
              <span style={{position:"absolute",right:10,top:"50%",transform:"translateY(-50%)",fontSize:14,color:f.conf>=85?"#047857":"#D97706"}}>{f.conf>=85?"✓":"⚠"}</span>
            </div>
          </div>
        ))}

        {/* Specialty select */}
        <div style={{marginBottom:10}}>
          <div style={{fontSize:10,fontWeight:700,color:"#64748B",textTransform:"uppercase",letterSpacing:"0.08em",marginBottom:4}}>Specialty</div>
          <select style={{width:"100%",height:40,borderRadius:12,border:"1px solid #E2E8F0",padding:"0 12px",fontSize:12,fontFamily:"Inter,sans-serif",background:"#fff",outline:"none"}}>
            <option>Cardiology</option><option>Gastroenterology</option><option>Medicine</option>
          </select>
          <div style={{fontSize:9,color:"#94A3B8",marginTop:2}}>Drives the specialty analytics</div>
        </div>

        {/* Location selects */}
        <div style={{display:"grid",gridTemplateColumns:"1fr 1fr 1fr",gap:8,marginBottom:10}}>
          {["District","Upazila","Territory"].map((l,i)=>(
            <div key={l}>
              <div style={{fontSize:10,fontWeight:700,color:"#64748B",textTransform:"uppercase",letterSpacing:"0.08em",marginBottom:4}}>{l}</div>
              <select style={{width:"100%",height:36,borderRadius:12,border:"1px solid #E2E8F0",padding:"0 6px",fontSize:11,fontFamily:"Inter,sans-serif",background:"#fff",outline:"none"}}>
                <option>{i===0?"Dhaka":i===1?"Select district first":"Select"}</option>
              </select>
            </div>
          ))}
        </div>
        <div style={{fontSize:9,color:"#94A3B8",marginBottom:10}}>Changing district clears Upazila and Territory</div>

        {/* Prescription Source */}
        <div style={{marginBottom:16}}>
          <div style={{fontSize:10,fontWeight:700,color:"#64748B",textTransform:"uppercase",letterSpacing:"0.08em",marginBottom:4}}>Prescription Source</div>
          <select style={{width:"100%",height:40,borderRadius:12,border:"1px solid #E2E8F0",padding:"0 12px",fontSize:12,fontFamily:"Inter,sans-serif",background:"#fff",outline:"none"}}>
            <option>Hospital</option><option>Private Chamber</option>
          </select>
          <div style={{fontSize:9,color:"#94A3B8",marginTop:2}}>Drives the source filter tag</div>
        </div>

        <div style={{display:"flex",gap:8}}>
          <GhostBtn onClick={onBack} style={{flex:"0 0 auto"}}>← Back</GhostBtn>
          <PrimaryBtn onClick={onNext} style={{flex:1,justifyContent:"center"}}>Next: Medicines →</PrimaryBtn>
        </div>
      </div>
    </div>
  );
}

// Verify – Medicines List
function VerifyMedicines({onNext,onBack}:{onNext:()=>void,onBack:()=>void}) {
  return (
    <div>
      <Card style={{marginBottom:12}}>
        <SectionLabel>Medicines Order & Confidence Review</SectionLabel>
        <div style={{fontSize:10,color:"#475569",marginBottom:12}}>
          Green ✓ high confidence · Orange 🤖 AI Guess &lt;80% · Tap a name to highlight it on the scan
        </div>

        <MedicineCardComponent brand="Napa" pct={96} type="Tablet" ingredient="Paracetamol" strength="500 mg" dosage="1+0+1" company="Square Pharmaceuticals PLC" verified="verified"/>
        <MedicineCardComponent brand="Seclo" pct={72} type="Capsule" ingredient="Omeprazole" strength="20 mg" dosage="0+0+1" company="Square Pharmaceuticals PLC" verified="verified" aiGuess/>
        <MedicineCardComponent brand="Amdocal" pct={88} type="Tablet" ingredient="Amlodipine" strength="5 mg" dosage="0+0+1" company="ACI Limited" verified="verified"/>
        <MedicineCardComponent brand="Azithro" pct={61} type="Tablet" ingredient="Azithromycin" strength="500 mg" dosage="1+0+0" company="Unknown" verified="none" manualFlag catalogueNote='Scan read "Azithro" - corrected from MedEx catalogue.'/>
      </Card>

      {/* Sticky footer */}
      <div style={{background:"#F8FAFC",borderTop:"1px solid #E2E8F0",padding:16,display:"flex",gap:8,marginTop:8}}>
        <GhostBtn onClick={onBack} style={{flex:"0 0 auto"}}>← Back</GhostBtn>
        <SuccessBtn onClick={onNext} icon="✓" style={{flex:1,justifyContent:"center"}}>Verify & Save to DB</SuccessBtn>
      </div>
    </div>
  );
}

// GPS / Territory strip & Save
function VerifyGPS({onSave,onBack}:{onSave:()=>void,onBack:()=>void}) {
  const [offTerritory, setOffTerritory] = useState(false);
  return (
    <Card>
      <SectionLabel>GPS & Territory</SectionLabel>
      <div style={{display:"grid",gridTemplateColumns:"1fr 1fr 1fr auto",gap:8,marginBottom:10}}>
        {["Upazila","District","Territory"].map((l,i)=>(
          <div key={l}>
            <div style={{fontSize:10,fontWeight:700,color:"#64748B",marginBottom:3}}>{l}</div>
            <input defaultValue={["Dhanmondi","Dhaka","Dhaka South"][i]} style={{width:"100%",height:32,borderRadius:8,border:"1px solid #E2E8F0",padding:"0 8px",fontSize:11,fontFamily:"Inter,sans-serif",outline:"none"}}/>
          </div>
        ))}
        <div style={{display:"flex",alignItems:"flex-end",paddingBottom:0}}>
          <GhostBtn style={{height:32,padding:"0 10px",fontSize:11}}>📍 GPS</GhostBtn>
        </div>
      </div>
      {/* Status */}
      <div style={{marginBottom:12}}>
        <button onClick={()=>setOffTerritory(f=>!f)} style={{fontSize:9,background:"none",border:"none",color:"#94A3B8",cursor:"pointer",marginBottom:4}}>Toggle territory state (demo)</button>
        {offTerritory ? (
          <div style={{fontSize:10,color:"#DC2626",display:"flex",alignItems:"center",gap:4}}>
            📍 Off-Territory Audit — scanned in Khulna, assigned Dhaka South
          </div>
        ) : (
          <div style={{fontSize:10,color:"#047857",display:"flex",alignItems:"center",gap:4}}>
            ✓ GPS pinned · 23.7465, 90.3760 · Dhaka South (in territory)
          </div>
        )}
      </div>
      <div style={{display:"flex",gap:8}}>
        <GhostBtn onClick={onBack} style={{flex:"0 0 auto"}}>← Back</GhostBtn>
        <SuccessBtn onClick={onSave} icon="✓" style={{flex:1,justifyContent:"center"}}>Verify & Save to DB</SuccessBtn>
      </div>
    </Card>
  );
}

// Scan Saved / Synced
function ScanSaved({onScanAnother,onAudit}:{onScanAnother:()=>void,onAudit:()=>void}) {
  return (
    <div>
      {/* Success toast */}
      <div style={{marginBottom:16}}>
        <Toast variant="success" message="Prescription saved and synced — Rx #A-128"/>
      </div>
      {/* Success card */}
      <Card style={{textAlign:"center",padding:32,marginBottom:16}}>
        <div style={{width:56,height:56,borderRadius:9999,background:"#ECFDF5",display:"flex",alignItems:"center",justifyContent:"center",fontSize:24,margin:"0 auto 16px"}}>✓</div>
        <div style={{fontSize:18,fontWeight:700,color:"#0F172A",marginBottom:6}}>Prescription saved</div>
        <div style={{fontSize:12,color:"#64748B",marginBottom:16}}>Rx #A-128 · 6 medicines · Dhaka South · MR001</div>
        <div style={{display:"flex",gap:8,justifyContent:"center"}}>
          <GhostBtn onClick={onAudit} style={{fontSize:12}}>Open audit summary</GhostBtn>
          <GhostBtn onClick={onScanAnother} style={{fontSize:12}}>Scan another</GhostBtn>
        </div>
      </Card>
      <RecentPrescriptions />
    </div>
  );
}

// ═══════════════════════════════════════════════════════════
// SHARED: RECENT PRESCRIPTIONS
// ═══════════════════════════════════════════════════════════
const recentRx = [
  {doctor:"Dr. A. K. M. Rahman",bmdc:"A-12345",meds:6,area:"Dhanmondi Dhaka",mr:"MR001",duplicate:false},
  {doctor:"Dr. Salma Begum",bmdc:"B-23456",meds:4,area:"Gulshan Dhaka",mr:"MR002",duplicate:false},
  {doctor:"Dr. Karim Uddin",bmdc:"C-34567",meds:8,area:"Motijheel Dhaka",mr:"MR001",duplicate:true},
];

function RecentPrescriptions({onSelect}:{onSelect?:(rx:typeof recentRx[0])=>void}) {
  return (
    <Card>
      <div style={{display:"flex",alignItems:"center",justifyContent:"space-between",marginBottom:12}}>
        <span style={{fontSize:14,fontWeight:600,color:"#0F172A"}}>Recent Prescriptions</span>
        <RefreshPill/>
      </div>
      {recentRx.length===0 ? (
        <EmptyState icon="📋" text="No history yet — scan your first prescription to unlock analytics"/>
      ) : recentRx.map((rx,i)=>(
        <div key={i} onClick={()=>onSelect&&onSelect(rx)} style={{display:"flex",alignItems:"center",gap:10,padding:"8px 10px",borderRadius:12,border:"1px solid #E2E8F0",background:"#fff",marginBottom:8,cursor:"pointer"}}>
          <div style={{width:40,height:40,borderRadius:8,background:"#F1F5F9",display:"flex",alignItems:"center",justifyContent:"center",fontSize:18,flexShrink:0}}>📋</div>
          <div style={{flex:1,minWidth:0}}>
            <div style={{display:"flex",alignItems:"center",gap:6,flexWrap:"wrap",marginBottom:2}}>
              <span style={{fontSize:12,fontWeight:500,color:"#0F172A"}}>{rx.doctor} • {rx.bmdc}</span>
              {rx.duplicate && <RegPill label="Duplicate Rx Detected" icon="⚠"/>}
            </div>
            <div style={{fontSize:10,color:"#64748B"}}>{rx.meds} meds • {rx.area} • {rx.mr} • tap for item breakdown</div>
          </div>
          <span style={{fontSize:14,color:"#CBD5E1"}}>›</span>
        </div>
      ))}
      {recentRx.length===0&&null}
    </Card>
  );
}

// ═══════════════════════════════════════════════════════════
// RX AUDIT SUMMARY (Frame 17)
// ═══════════════════════════════════════════════════════════
function RxAuditSummary({onBack,onPitchCard}:{onBack:()=>void,onPitchCard:()=>void}) {
  const [filter,setFilter] = useState("All");
  const medicines = [
    {brand:"Napa",strength:"500 mg",type:"Tablet",dosage:"1+0+1",generic:"Paracetamol",company:"Square Pharmaceuticals PLC",own:true,conf:96,neml:true,abx:false,abxBroad:false,dgda:false,trips:false},
    {brand:"Seclo",strength:"20 mg",type:"Capsule",dosage:"0+0+1",generic:"Omeprazole",company:"Square Pharmaceuticals PLC",own:true,conf:88,neml:true,abx:false,abxBroad:false,dgda:false,trips:false},
    {brand:"Azithro",strength:"500 mg",type:"Tablet",dosage:"1+0+0",generic:"Azithromycin",company:"Incepta Pharmaceuticals",own:false,conf:72,neml:false,abx:true,abxBroad:false,dgda:false,trips:true},
    {brand:"Cefixol",strength:"200 mg",type:"Capsule",dosage:"1+0+1",generic:"Cefixime",company:"Beximco Pharmaceuticals",own:false,conf:96,neml:false,abx:true,abxBroad:true,dgda:false,trips:false},
    {brand:"Amlovas",strength:"5 mg",type:"Tablet",dosage:"0+0+1",generic:"Amlodipine Besylate",company:"ACI Limited",own:false,conf:90,neml:true,abx:false,abxBroad:false,dgda:true,trips:false},
    {brand:"Pantop",strength:"40 mg",type:"Tablet",dosage:"1+0+0",generic:"Pantoprazole",company:"Renata Limited",own:false,conf:85,neml:false,abx:false,abxBroad:false,dgda:false,trips:false},
  ];
  const filters = [{label:"All",count:6},{label:"Own Pharma",count:2},{label:"Competitors",count:4},{label:"<80%",count:1}];
  const filtered = filter==="All"?medicines:filter==="Own Pharma"?medicines.filter(m=>m.own):filter==="Competitors"?medicines.filter(m=>!m.own):medicines.filter(m=>m.conf<80);

  const classColors = ["#1E40AF","#059669","#D97706","#0891B2","#7C3AED","#DC2626"];
  const classes = [{label:"Cardiology",pct:33},{label:"Gastroenterology",pct:17},{label:"Antibiotics",pct:17},{label:"Analgesics",pct:17},{label:"Other",pct:16}];

  return (
    <div>
      {/* Header card */}
      <Card style={{marginBottom:12}}>
        <div style={{display:"flex",alignItems:"flex-start",gap:8,flexWrap:"wrap",marginBottom:6}}>
          <span style={{fontSize:14,fontWeight:700,color:"#0F172A"}}>📄 Prescription Audit Summary — Rx #A-128</span>
          <RegPill label="Duplicate Rx Detected" icon="⚠"/>
        </div>
        <div style={{fontSize:11,color:"#64748B"}}>Dr. A. K. M. Rahman (Cardiology) • 6 Medicines Detected • MR MR001 • Dhaka</div>
      </Card>

      {/* Filter pills */}
      <div style={{display:"flex",gap:6,marginBottom:12,overflowX:"auto"}}>
        {filters.map(f=>(
          <button key={f.label} onClick={()=>setFilter(f.label)} style={{padding:"6px 12px",borderRadius:9999,border:`1px solid ${filter===f.label?"transparent":"#E2E8F0"}`,background:filter===f.label?"#0F172A":"#fff",color:filter===f.label?"#fff":"#475569",fontSize:11,fontWeight:600,cursor:"pointer",whiteSpace:"nowrap"}}>
            {f.label} ({f.count})
          </button>
        ))}
      </div>

      {/* Clinical strip */}
      <Card style={{marginBottom:12}}>
        <div style={{display:"flex",flexWrap:"wrap",gap:6,marginBottom:12}}>
          <StatusPill variant="amber">🦠 Antibiotic Stewardship: 3 in Rx · 1 broad-spectrum</StatusPill>
          <StatusPill variant="red">📍 Off-Territory Audit</StatusPill>
          <StatusPill variant="slate">6 Meds Prescribed</StatusPill>
        </div>
        <div style={{fontSize:9,fontWeight:700,color:"#475569",textTransform:"uppercase",letterSpacing:"0.1em",marginBottom:6,display:"flex",alignItems:"center",gap:4}}>🥧 Therapeutic Class Breakdown</div>
        <div style={{display:"flex",height:12,borderRadius:9999,overflow:"hidden",marginBottom:6}}>
          {classes.map((c,i)=>(
            <div key={i} style={{flex:c.pct,background:classColors[i],height:"100%"}}/>
          ))}
        </div>
        <div style={{display:"flex",flexWrap:"wrap",gap:4}}>
          {classes.map((c,i)=>(
            <span key={i} style={{fontSize:9,color:"#475569",display:"flex",alignItems:"center",gap:2}}>
              <span style={{width:6,height:6,borderRadius:"50%",background:classColors[i],display:"inline-block"}}/>
              {c.label}: {c.pct}%
            </span>
          ))}
        </div>
      </Card>

      {/* Duplicate fraud note */}
      <div style={{background:"#FEF2F2",border:"1px solid #FECACA",borderRadius:12,padding:12,marginBottom:12,fontSize:11,color:"#B31D1D"}}>
        Fraud alert: this physical prescription appears to have been scanned before — first captured as Rx #A-104 (MR004, 2026-08-02 14:11). Excluded from target credit pending RSM review.
      </div>

      {/* Item list */}
      {filtered.map((m,i)=>(
        <div key={i} style={{background:m.conf<80?"#FFF7ED":"#fff",border:"1px solid #E2E8F0",borderRadius:12,padding:12,marginBottom:8}}>
          <div style={{marginBottom:6}}>
            <span style={{fontSize:13,fontWeight:600,color:"#0F172A",textDecoration:"underline dotted",textDecorationColor:"#CBD5E1",cursor:"pointer"}}>{m.brand} {m.strength}</span>
            <span style={{fontSize:10,color:"#94A3B8",marginLeft:8}}>{m.type} • {m.dosage}</span>
          </div>
          {/* Chips row */}
          <div style={{display:"flex",flexWrap:"wrap",gap:4,marginBottom:6}}>
            {m.neml && <RegPill label="NEML Listed"/>}
            {m.abxBroad && <RegPill label="ABX ★" icon="★"/>}
            {m.abx && !m.abxBroad && <RegPill label="ABX"/>}
            {m.dgda && <RegPill label="DGDA Price Alert" icon="🚫"/>}
            {m.trips && <RegPill label="TRIPS Watch"/>}
            {!m.own && <RegPill label="Duplicate Rx Detected"/>}
            <StatusPill variant="slate">{m.type==="Capsule"?"GI":"Cardiology"}</StatusPill>
          </div>
          {/* Portfolio match for own brands */}
          {!m.own && (
            <div style={{display:"flex",gap:6,flexWrap:"wrap",marginBottom:6}}>
              <button onClick={()=>{}} style={{padding:"4px 10px",borderRadius:9999,border:"1px solid #C4B5FD",background:"#EDE9FE",color:"#7C3AED",fontSize:10,fontWeight:600,cursor:"pointer"}}>✨ Own Portfolio Match: Opal</button>
              <button onClick={onPitchCard} style={{padding:"4px 10px",borderRadius:9999,border:"none",background:"#7C3AED",color:"#fff",fontSize:10,fontWeight:600,cursor:"pointer"}}>🪪 Generate Doctor Pitch Card</button>
            </div>
          )}
          {/* Low-confidence link */}
          {m.conf<80 && <div style={{fontSize:11,color:"#EA580C",textDecoration:"underline",cursor:"pointer",marginBottom:6}}>❔ Verify against Medex</div>}
          {/* Generic + company */}
          <div style={{fontSize:12,color:"#475569",marginBottom:4}}>{m.generic}</div>
          <div style={{display:"flex",alignItems:"center",gap:6,justifyContent:"space-between"}}>
            <div style={{display:"flex",alignItems:"center",gap:4}}>
              <CompanyBadge name={m.company} size={20}/>
              <span style={{fontSize:11,fontWeight:m.own?700:400,color:m.own?"#7C3AED":"#94A3B8",fontStyle:m.own?"normal":"italic"}}>
                {m.company} {m.own&&"🏠"}
              </span>
            </div>
            <ConfBadge pct={m.conf}/>
          </div>
        </div>
      ))}

      {/* Market share summary */}
      <Card style={{marginTop:8}}>
        <div style={{fontSize:11,fontWeight:700,color:"#475569",marginBottom:8}}>💼 Market Share Summary for this Rx:</div>
        <div style={{fontSize:11,color:"#047857",marginBottom:4}}>• Square Pharmaceuticals PLC: 2 / 6 (33%)</div>
        <div style={{fontSize:11,color:"#D97706",marginBottom:12}}>• Competitor brands identified: 4 / 6 (67%)</div>
        <div style={{display:"flex",gap:8,flexWrap:"wrap"}}>
          <GhostBtn icon="📄" style={{fontSize:11,color:"#059669",flex:1,justifyContent:"center"}}>Export Rx CSV</GhostBtn>
          <GhostBtn icon="📋" style={{fontSize:11,color:"#2563EB",flex:1,justifyContent:"center"}}>Copy to Clipboard</GhostBtn>
        </div>
        <div style={{fontSize:10,color:"#94A3B8",marginTop:8,textAlign:"right"}}>Long-press a medicine name to preview the prescription crop</div>
      </Card>
    </div>
  );
}

// Doctor Pitch Card (Frame 20)
function DoctorPitchCard({onClose}:{onClose:()=>void}) {
  return (
    <div style={{position:"fixed",inset:0,zIndex:60,background:"rgba(0,0,0,0.5)",display:"flex",flexDirection:"column",justifyContent:"flex-end"}}>
      <div style={{background:"#fff",borderRadius:"16px 16px 0 0",maxHeight:"88vh",overflow:"auto"}}>
        {/* Header */}
        <div style={{background:"linear-gradient(90deg,#7C3AED,#4F46E5)",padding:"20px 16px",borderRadius:"16px 16px 0 0",position:"sticky",top:0}}>
          <div style={{display:"flex",alignItems:"center",justifyContent:"space-between",marginBottom:4}}>
            <span style={{fontSize:14,fontWeight:700,color:"#fff"}}>🩺 Doctor Pitch Card</span>
            <button onClick={onClose} style={{width:32,height:32,borderRadius:9999,border:"none",background:"rgba(255,255,255,0.2)",color:"#fff",cursor:"pointer",fontSize:16}}>✕</button>
          </div>
          <div style={{fontSize:10,color:"rgba(255,255,255,0.80)"}}>Dr. A. K. M. Rahman · Rx #A-128 · Seclo → Opal</div>
        </div>

        <div style={{padding:16}}>
          {/* Compliance pills */}
          <div style={{display:"flex",flexWrap:"wrap",gap:4,marginBottom:12}}>
            <RegPill label="NEML Listed"/><RegPill label="DGDA Price Alert" icon="🚫"/>
          </div>

          {/* Compare table */}
          <div style={{border:"1px solid #E2E8F0",borderRadius:12,overflow:"hidden",marginBottom:12}}>
            <div style={{display:"grid",gridTemplateColumns:"auto 1fr 1fr",background:"#F8FAFC"}}>
              {["","Competitor","Your Brand"].map((h,i)=>(
                <div key={i} style={{padding:"8px 10px",fontSize:10,fontWeight:600,color:i===2?"#7C3AED":"#94A3B8",borderBottom:"1px solid #E2E8F0"}}>{h}</div>
              ))}
            </div>
            {[
              ["Brand","Seclo","Opal"],
              ["Company","Square Pharma","Healthcare Pharma Ltd."],
              ["Generic","Omeprazole","Omeprazole"],
              ["Strength/Form","20 mg Capsule","20 mg Capsule"],
              ["MRP","120 BDT (10's)","105 BDT (10's)"],
            ].map((row,i)=>(
              <div key={i} style={{display:"grid",gridTemplateColumns:"auto 1fr 1fr",borderBottom:i<4?"1px solid #F1F5F9":"none"}}>
                <div style={{padding:"8px 10px",fontSize:10,color:"#94A3B8",whiteSpace:"nowrap"}}>{row[0]}</div>
                <div style={{padding:"8px 10px",fontSize:11,color:"#475569"}}>{row[1]}</div>
                <div style={{padding:"8px 10px",fontSize:11,fontWeight:700,color:"#7C3AED"}}>{row[2]}</div>
              </div>
            ))}
          </div>

          {/* Price position */}
          <div style={{background:"#ECFDF5",border:"1px solid #A7F3D0",borderRadius:10,padding:"8px 12px",marginBottom:12,fontSize:11,color:"#047857",display:"flex",alignItems:"center",gap:6}}>
            ⚖️ Price position: 12.5% lower per unit than Seclo
          </div>

          {/* Bioequivalence */}
          <div style={{background:"rgba(239,246,255,0.60)",border:"1px solid #DBEAFE",borderRadius:12,padding:12,marginBottom:12}}>
            <div style={{fontSize:9,fontWeight:700,color:"#1D4ED8",textTransform:"uppercase",letterSpacing:"0.1em",marginBottom:6}}>🧪 Bioequivalence & dosage evidence</div>
            <div style={{fontSize:11,color:"#334155",lineHeight:1.5,marginBottom:6}}>Opal (Omeprazole 20mg) is DGDA-registered with full bioequivalence certification against the originator product.</div>
            <div style={{fontSize:11,color:"#334155",lineHeight:1.5}}>Both products share identical active moiety, mechanism, onset, and pharmacokinetic profile under standard dosing conditions.</div>
          </div>

          {/* Pitch script */}
          <div style={{background:"#F8FAFC",border:"1px solid #E2E8F0",borderRadius:12,padding:12,marginBottom:12}}>
            <div style={{fontSize:9,fontWeight:700,color:"#64748B",textTransform:"uppercase",letterSpacing:"0.1em",marginBottom:6}}>📢 Smart pitch script</div>
            <div style={{fontSize:11,color:"#334155",fontStyle:"italic",lineHeight:1.6}}>
              "Doctor sahib, you have been prescribing Seclo for Omeprazole therapy. Our Opal contains the same 20mg Omeprazole with a DGDA bioequivalence certificate, priced 12.5% lower — delivering the same clinical outcome at a more accessible cost for your patients. Would you consider switching Seclo to Opal in your next round of prescriptions?"
            </div>
          </div>

          <div style={{fontSize:9,color:"#94A3B8",marginBottom:12}}>Bioequivalence & pack data from the MedEx-audited catalogue · verify sample stock before the visit.</div>

          {/* Footer */}
          <div style={{display:"flex",gap:8}}>
            <PrimaryBtn icon="📑" style={{flex:1,justifyContent:"center",background:"#DC2626"}}>Download PDF</PrimaryBtn>
            <GhostBtn icon="📋" style={{flex:1,justifyContent:"center",fontSize:12}}>Copy pitch script</GhostBtn>
          </div>
          <div style={{fontSize:9,color:"#94A3B8",textAlign:"right",marginTop:8}}>Show during chamber visit</div>
        </div>
      </div>
    </div>
  );
}

// ═══════════════════════════════════════════════════════════
// ANALYTICS SCREENS
// ═══════════════════════════════════════════════════════════
const barData = [
  {name:"Napa",value:148},{name:"Seclo",value:112},{name:"Amdocal",value:94},
  {name:"Azithro",value:87},{name:"Pantop",value:76},{name:"Cefixol",value:61},
];
const donutData = [
  {name:"Square Pharma",value:38},{name:"Incepta",value:22},{name:"Beximco",value:18},
  {name:"ACI Limited",value:12},{name:"Renata",value:7},{name:"Others",value:3},
];
const stackedData = [
  {specialty:"Cardiology",square:45,incepta:20,beximco:15,aci:10,renata:10},
  {specialty:"Gastro",square:30,incepta:35,beximco:20,aci:5,renata:10},
  {specialty:"Medicine",square:25,incepta:30,beximco:25,aci:12,renata:8},
  {specialty:"Orthopedics",square:20,incepta:25,beximco:30,aci:15,renata:10},
];
const sparkData = [{v:30},{v:38},{v:35},{v:42},{v:38},{v:45},{v:43}];

function LiveRecentScans() {
  const scans = [
    {time:"2m ago",doctor:"Dr. A. K. M. Rahman",specialty:"Cardiology",brand:"Napa 500mg Tablet",company:"Square Pharmaceuticals PLC",verified:true,conf:96,location:"Dhanmondi, Dhaka"},
    {time:"8m ago",doctor:"Dr. Salma Begum",specialty:"Gastroenterology",brand:"Seclo 20mg Capsule",company:"Square Pharmaceuticals PLC",verified:true,conf:88,location:"Gulshan, Dhaka"},
    {time:"15m ago",doctor:"Dr. Karim Uddin",specialty:"Medicine",brand:"Azithro 500mg Tablet",company:"Incepta Pharmaceuticals",verified:false,conf:72,location:"Motijheel, Dhaka"},
    {time:"22m ago",doctor:"Dr. Farida Haque",specialty:"Cardiology",brand:"Amdocal 5mg Tablet",company:"ACI Limited",verified:true,conf:91,location:"Dhanmondi, Dhaka"},
    {time:"31m ago",doctor:"Dr. Babul Islam",specialty:"Orthopedics",brand:"Cefixol 200mg Capsule",company:"Beximco Pharmaceuticals",verified:true,conf:85,location:"Mirpur, Dhaka"},
  ];
  const [sort,setSort] = useState("Time");
  const sortCols = ["Time","Doctor","Medicine","Company","Conf."];

  return (
    <Card style={{padding:20}}>
      <div style={{marginBottom:12}}>
        <div style={{display:"flex",alignItems:"center",justifyContent:"space-between",marginBottom:6}}>
          <div style={{display:"flex",alignItems:"center",gap:8}}>
            <div style={{position:"relative",width:8,height:8,marginRight:4}}>
              <div style={{width:8,height:8,borderRadius:"50%",background:"#10B981",position:"absolute"}}/>
              <div style={{width:8,height:8,borderRadius:"50%",background:"#10B981",position:"absolute",animation:"ping 1.5s ease-in-out infinite",opacity:0.6}}/>
            </div>
            <span style={{fontSize:14,fontWeight:600,color:"#0F172A"}}>Live Recent Scans</span>
          </div>
          <div style={{display:"flex",gap:6}}>
            <GhostBtn style={{fontSize:10,padding:"4px 8px"}}>CSV</GhostBtn>
            <button style={{width:28,height:28,borderRadius:8,border:"1px solid #E2E8F0",background:"#fff",cursor:"pointer",fontSize:12}}>🔄</button>
          </div>
        </div>
        <div style={{fontSize:10,color:"#64748B",marginBottom:8}}>Every individual medicine detected, per MR</div>
        {/* Tag chips */}
        <div style={{display:"flex",alignItems:"center",gap:6,marginBottom:8}}>
          <span style={{fontSize:10,fontWeight:700,color:"#94A3B8",textTransform:"uppercase"}}>Tags</span>
          <FilterChip label="Hospital" active/>
          <FilterChip label="Private Chamber"/>
        </div>
        {/* Sort bar */}
        <div style={{display:"flex",gap:4,overflowX:"auto"}}>
          {sortCols.map(col=>(
            <button key={col} onClick={()=>setSort(col)} style={{padding:"3px 8px",borderRadius:9999,border:`1px solid ${sort===col?"#0F172A":"#E2E8F0"}`,background:sort===col?"#0F172A":"#fff",color:sort===col?"#fff":"#475569",fontSize:10,fontWeight:600,cursor:"pointer",whiteSpace:"nowrap"}}>
              {col} {sort===col?"↑":"↕"}
            </button>
          ))}
        </div>
      </div>

      {/* Scan rows */}
      {scans.map((s,i)=>(
        <div key={i} style={{background:"#fff",border:"1px solid #F1F5F9",borderRadius:12,padding:12,marginBottom:8}}>
          <div style={{display:"flex",alignItems:"flex-start",justifyContent:"space-between",marginBottom:4}}>
            <div>
              <span style={{fontSize:13,fontWeight:600,color:"#0F172A"}}>{s.brand.split(" ")[0]} </span>
              <span style={{fontSize:10,color:"#64748B"}}>{s.brand.replace(s.brand.split(" ")[0]+" ","")}</span>
            </div>
            <ConfBadge pct={s.conf}/>
          </div>
          <div style={{display:"flex",flexWrap:"wrap",gap:4,marginBottom:4}}>
            <div style={{display:"flex",alignItems:"center",gap:3}}>
              <div style={{width:8,height:8,borderRadius:"50%",background:s.verified?"#10B981":"#F59E0B",flexShrink:0}}/>
              <span style={{fontSize:10,color:"#475569"}}>{s.company.length>22?s.company.slice(0,22)+"…":s.company}</span>
              <span style={{fontSize:10}}>{s.verified?"✓":"⚠"}</span>
            </div>
            <StatusPill variant="slate">{s.specialty}</StatusPill>
          </div>
          <div style={{fontSize:10,color:"#64748B"}}>{s.doctor} · {s.time} · {s.location}</div>
        </div>
      ))}

      <div style={{display:"flex",alignItems:"center",justifyContent:"space-between",marginTop:8}}>
        <span style={{fontSize:11,color:"#64748B"}}>Showing 1–25 of 1,284 medicines</span>
        <div style={{display:"flex",gap:6}}>
          <GhostBtn style={{fontSize:11,padding:"4px 10px"}}>Prev</GhostBtn>
          <GhostBtn style={{fontSize:11,padding:"4px 10px"}}>Next</GhostBtn>
        </div>
      </div>
    </Card>
  );
}

function AnalyticsOverview({onDrilldown}:{onDrilldown?:()=>void}) {
  const [showFilterSheet,setShowFilterSheet] = useState(false);

  return (
    <div>
      {/* Dark hero */}
      <DarkHero style={{marginBottom:16}}>
        <div style={{fontSize:20,fontWeight:700,color:"#fff",marginBottom:6,letterSpacing:"-0.02em"}}>Prescription Capture</div>
        <div style={{fontSize:13,color:"rgba(255,255,255,0.8)",marginBottom:12}}>Drop prescription image here or click to upload. Supports camera, rotation, contrast.</div>
        <div style={{border:"1px dashed rgba(255,255,255,0.3)",borderRadius:12,padding:16,background:"rgba(255,255,255,0.08)",backdropFilter:"blur(4px)",textAlign:"center"}}>
          <div style={{fontSize:20,marginBottom:4}}>☁</div>
          <div style={{fontSize:13,fontWeight:500,color:"#fff"}}>Drop prescription or click</div>
          <div style={{fontSize:11,color:"rgba(255,255,255,0.7)"}}>Full-width banner - primary action</div>
        </div>
      </DarkHero>

      {/* Filter bar */}
      <Card style={{marginBottom:16}}>
        <div style={{display:"flex",alignItems:"center",justifyContent:"space-between",marginBottom:8}}>
          <span style={{fontSize:11,fontWeight:700,color:"#64748B",textTransform:"uppercase",letterSpacing:"0.08em"}}>⚡ Filters</span>
          <div style={{display:"flex",gap:6,alignItems:"center"}}>
            <span style={{fontSize:10,color:"#94A3B8"}}>2 active</span>
            <PrimaryBtn onClick={()=>setShowFilterSheet(true)} style={{fontSize:11,padding:"6px 12px"}}>⚡ Filters (2)</PrimaryBtn>
            <PrimaryBtn icon="📄" style={{fontSize:11,padding:"6px 12px"}}>Export Data</PrimaryBtn>
          </div>
        </div>
        <div style={{fontSize:11,color:"#94A3B8"}}>Dhaka South · Last 30 Days · All Specialties · All MRs</div>
      </Card>

      {/* KPI cards */}
      <div style={{marginBottom:16}}>
        <div style={{display:"flex",alignItems:"center",gap:6,marginBottom:10}}>
          <span style={{color:"#1D4ED8",fontSize:14}}>📈</span>
          <span style={{fontSize:14,fontWeight:600,color:"#0F172A"}}>Top Summary KPIs</span>
        </div>
        <div style={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:12}}>
          <KPICard label="Total Captured" value="342" delta="12%" deltaUp icon="📋" iconBg="#EFF6FF" today="0" week="0" month="38"/>
          <KPICard label="Identified Medicines" value="1,284" delta="8%" deltaUp icon="💊" iconBg="#ECFDF5" today="12" week="89" month="412"/>
          <KPICard label="Target Share" value="38%" delta="3%" deltaUp icon="🥧" iconBg="#FEF3C7" track={38}/>
          <KPICard label="Active Doctor Coverage" value="24" delta="2" deltaUp icon="👨‍⚕️" iconBg="#EDE9FE"/>
        </div>
      </div>

      {/* Chart A – Bar */}
      <Card style={{marginBottom:16}}>
        <SectionHeader icon="📊" title="A. Most Prescribed Medicines — Bar Chart"/>
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={barData} margin={{left:-20,right:8,top:4,bottom:24}}>
            <XAxis dataKey="name" tick={{fontSize:10,fill:"#64748B"}} angle={-35} textAnchor="end" interval={0}/>
            <YAxis tick={{fontSize:10,fill:"#64748B"}}/>
            <Tooltip contentStyle={{fontSize:11,borderRadius:8,border:"1px solid #E2E8F0"}}/>
            <Bar dataKey="value" radius={[4,4,0,0]}>
              {barData.map((_,i)=><Cell key={i} fill={C.chart[i%6]}/>)}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </Card>

      {/* Chart B – Donut */}
      <Card style={{marginBottom:16}}>
        <SectionHeader icon="🥧" title="B. Company Share of Voice — Donut"/>
        <div style={{position:"relative"}}>
          <ResponsiveContainer width="100%" height={220}>
            <PieChart>
              <Pie data={donutData} cx="50%" cy="50%" innerRadius={60} outerRadius={90} paddingAngle={2} dataKey="value">
                {donutData.map((_,i)=><Cell key={i} fill={C.chart[i%6]}/>)}
              </Pie>
              <Tooltip contentStyle={{fontSize:11,borderRadius:8}}/>
            </PieChart>
          </ResponsiveContainer>
          <div style={{position:"absolute",top:"50%",left:"50%",transform:"translate(-50%,-50%)",textAlign:"center",pointerEvents:"none"}}>
            <div style={{fontSize:16,fontWeight:700,color:"#0F172A"}}>SoV 38%</div>
          </div>
        </div>
        <div style={{display:"flex",flexDirection:"column",gap:4,marginTop:8}}>
          {donutData.map((d,i)=>(
            <div key={i} style={{display:"flex",alignItems:"center",gap:8,fontSize:11}}>
              <div style={{width:10,height:10,borderRadius:2,background:C.chart[i%6],flexShrink:0}}/>
              <span style={{flex:1,color:"#475569"}}>{d.name}</span>
              <span style={{fontWeight:600,color:"#0F172A"}}>{d.value}%</span>
            </div>
          ))}
        </div>
      </Card>

      {/* Widget C – Doctor leaderboard */}
      <Card style={{marginBottom:16}}>
        <SectionHeader icon="👨‍⚕️" title="C. Top Doctor Prescribers — Leaderboard"/>
        {[
          {rank:1,name:"Dr. A. K. M. Rahman",meta:"Cardiology • Ibn Sina Chamber • Dhaka",own:12,comp:5,conv:67,rx:18,sov:71},
          {rank:2,name:"Dr. Salma Begum",meta:"Gastroenterology • Square Hospital • Dhaka",own:9,comp:7,conv:56,rx:14,sov:56},
          {rank:3,name:"Dr. Karim Uddin",meta:"Medicine • Private Chamber • Motijheel",own:6,comp:11,conv:35,rx:12,sov:35},
        ].map(doc=>(
          <div key={doc.rank} style={{display:"flex",alignItems:"center",gap:10,padding:"10px 12px",border:"1px solid #E2E8F0",borderRadius:12,marginBottom:8}}>
            <div style={{width:28,height:28,borderRadius:9999,background:"#EFF6FF",display:"flex",alignItems:"center",justifyContent:"center",fontSize:11,fontWeight:700,color:"#1E40AF",flexShrink:0}}>{doc.rank}</div>
            <div style={{flex:1,minWidth:0}}>
              <div style={{fontSize:12,fontWeight:600,color:"#0F172A"}}>{doc.name}</div>
              <div style={{fontSize:10,color:"#64748B",marginBottom:4}}>{doc.meta}</div>
              <ProgressBar pct={doc.sov}/>
            </div>
            <div style={{textAlign:"right",flexShrink:0}}>
              <div style={{fontSize:11,fontWeight:700}}><span style={{color:"#047857"}}>{doc.own}</span><span style={{color:"#94A3B8"}}> / {doc.comp}</span></div>
              <div style={{fontSize:10,fontWeight:600,color:doc.conv>=50?"#059669":"#D97706"}}>{doc.conv}% conv</div>
              <div style={{fontSize:9,color:"#94A3B8"}}>{doc.rx} Rx</div>
            </div>
          </div>
        ))}
        <div style={{display:"flex",alignItems:"center",justifyContent:"space-between"}}>
          <span style={{fontSize:10,color:"#64748B"}}>1–3 of 42</span>
          <div style={{display:"flex",gap:6}}>
            <GhostBtn style={{fontSize:11,padding:"4px 10px"}}>Prev</GhostBtn>
            <GhostBtn style={{fontSize:11,padding:"4px 10px"}}>Next</GhostBtn>
          </div>
        </div>
      </Card>

      {/* Chart D – Stacked bar */}
      <Card style={{marginBottom:16}}>
        <SectionHeader icon="📊" title="D. Generic vs Brand Matrix — Stacked Bar by Specialty"/>
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={stackedData} layout="vertical" margin={{left:60,right:8,top:4,bottom:4}}>
            <XAxis type="number" tick={{fontSize:10,fill:"#64748B"}} hide/>
            <YAxis type="category" dataKey="specialty" tick={{fontSize:10,fill:"#64748B"}} width={60}/>
            <Tooltip contentStyle={{fontSize:11,borderRadius:8}}/>
            <Legend iconSize={8} wrapperStyle={{fontSize:10}}/>
            <Bar dataKey="square" name="Square" stackId="a" fill={C.chart[0]}/>
            <Bar dataKey="incepta" name="Incepta" stackId="a" fill={C.chart[1]}/>
            <Bar dataKey="beximco" name="Beximco" stackId="a" fill={C.chart[2]}/>
            <Bar dataKey="aci" name="ACI" stackId="a" fill={C.chart[3]}/>
            <Bar dataKey="renata" name="Renata" stackId="a" fill={C.chart[4]} radius={[0,4,4,0]}/>
          </BarChart>
        </ResponsiveContainer>
      </Card>

      <LiveRecentScans/>
      <div style={{marginTop:16}}><RecentPrescriptions/></div>

      {showFilterSheet && <FilterSheet onClose={()=>setShowFilterSheet(false)}/>}
    </div>
  );
}

// Filter Bottom Sheet (Frame 14)
function FilterSheet({onClose}:{onClose:()=>void}) {
  const [dateRange,setDateRange] = useState("Last 30 Days");
  const ranges = ["All Time","Last 7 Days","Last 30 Days","Last 90 Days","Last 12 Months"];
  return (
    <div style={{position:"fixed",inset:0,zIndex:50}}>
      <div onClick={onClose} style={{position:"absolute",inset:0,background:"rgba(0,0,0,0.4)"}}/>
      <div style={{position:"absolute",bottom:0,left:0,right:0,background:"#fff",borderRadius:"16px 16px 0 0",maxHeight:"90vh",overflow:"auto"}}>
        <div style={{display:"flex",justifyContent:"center",padding:"12px 0 0"}}>
          <div style={{width:40,height:4,borderRadius:9999,background:"#E2E8F0"}}/>
        </div>
        <div style={{display:"flex",alignItems:"center",justifyContent:"space-between",padding:"12px 16px",borderBottom:"1px solid #E2E8F0"}}>
          <div style={{display:"flex",alignItems:"center",gap:6}}>
            <span style={{color:"#2563EB"}}>⚡</span>
            <span style={{fontSize:16,fontWeight:700,color:"#0F172A"}}>Filters</span>
          </div>
          <GhostBtn style={{fontSize:11,padding:"4px 10px"}}>Reset</GhostBtn>
        </div>
        <div style={{padding:16}}>
          {["Territory","District","Specialty","MR"].map(l=>(
            <div key={l} style={{marginBottom:12}}>
              <div style={{fontSize:10,fontWeight:700,color:"#64748B",textTransform:"uppercase",letterSpacing:"0.08em",marginBottom:4}}>{l}</div>
              <select style={{width:"100%",height:40,borderRadius:12,border:"1px solid #E2E8F0",padding:"0 12px",fontSize:12,fontFamily:"Inter,sans-serif",background:"#fff",outline:"none"}}>
                <option>All {l}s</option>
              </select>
            </div>
          ))}
          <div style={{marginBottom:12}}>
            <div style={{fontSize:10,fontWeight:700,color:"#64748B",textTransform:"uppercase",letterSpacing:"0.08em",marginBottom:6}}>Source</div>
            <div style={{display:"flex",gap:8}}>
              <FilterChip label="Hospital" active/><FilterChip label="Private Chamber"/>
            </div>
          </div>
          <div style={{marginBottom:16}}>
            <div style={{fontSize:10,fontWeight:700,color:"#64748B",textTransform:"uppercase",letterSpacing:"0.08em",marginBottom:6}}>Date Range</div>
            <div style={{display:"flex",flexWrap:"wrap",gap:6}}>
              {ranges.map(r=>(
                <FilterChip key={r} label={r} active={r===dateRange} onClick={()=>setDateRange(r)}/>
              ))}
            </div>
          </div>
          <div style={{fontSize:10,color:"#94A3B8",marginBottom:12}}>2 filters active</div>
          <div style={{display:"flex",gap:8}}>
            <GhostBtn onClick={onClose} style={{flex:1,justifyContent:"center"}}>Cancel</GhostBtn>
            <PrimaryBtn onClick={onClose} style={{flex:1,justifyContent:"center"}}>Apply & Export Data</PrimaryBtn>
          </div>
        </div>
      </div>
    </div>
  );
}

// ═══════════════════════════════════════════════════════════
// HUB SCREENS
// ═══════════════════════════════════════════════════════════
const hubTabs = ["💊 25K+ Drug Index","🌐 TRIPS Waiver","📰 Industry News","💼 Jobs","🗓️ Health Days"];

function HubDrugIndex() {
  const products = [
    {brand:"Napa",form:"Tablet 500 mg",generic:"Paracetamol",company:"Square Pharmaceuticals PLC"},
    {brand:"Seclo",form:"Capsule 20 mg",generic:"Omeprazole",company:"Square Pharmaceuticals PLC"},
    {brand:"Cardivask",form:"Tablet 5 mg",generic:"Amlodipine Besylate",company:"Incepta Pharmaceuticals Ltd."},
    {brand:"Azithro",form:"Tablet 500 mg",generic:"Azithromycin Dihydrate",company:"Incepta Pharmaceuticals Ltd."},
    {brand:"Renova",form:"Capsule 40 mg",generic:"Pantoprazole Sodium Sesquihydrate",company:"Renata Limited"},
  ];
  return (
    <div>
      <Card style={{marginBottom:12}}>
        <SectionHeader icon="🗄" title="25K+ Drug Index & Search"/>
        <div style={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:8,marginBottom:12}}>
          {[{l:"Total medicines",v:"25,341"},{l:"Dosage Forms",v:"142"},{l:"Companies",v:"247"},{l:"DGDA Registered",v:"24,988"}].map(t=>(
            <MiniKPI key={t.l} label={t.l} value={t.v}/>
          ))}
        </div>
        <div style={{display:"flex",flexWrap:"wrap",gap:6,marginBottom:12}}>
          {["🏆 Top 10 Pharma","🫀 Cardiology","🦠 Antibiotics","💊 OTC"].map((f,i)=>(
            <FilterChip key={f} label={f} active={i===0}/>
          ))}
        </div>
        <input placeholder="Search Napa, Seclo, Injection, Square, Omeprazole..." style={{width:"100%",height:40,borderRadius:12,border:"1px solid #E2E8F0",padding:"0 12px",fontSize:13,fontFamily:"Inter,sans-serif",outline:"none",marginBottom:8,boxSizing:"border-box"}}/>
        <div style={{fontSize:11,color:"#64748B",display:"flex",alignItems:"center",gap:4,marginBottom:12}}>
          <span style={{color:"#2563EB"}}>⚡</span>Showing curated Top 10 Pharma slice — 24 results from the 25K catalogue. Every product is DGDA-registered.
        </div>
      </Card>

      {/* Popular medicines hero (Frame 23) */}
      <DarkHero style={{marginBottom:16}}>
        <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:6}}>
          <span style={{fontSize:16}}>🔥</span>
          <span style={{fontSize:16,fontWeight:700,color:"#fff"}}>Currently Popular Medicines</span>
        </div>
        <div style={{fontSize:12,color:"rgba(255,255,255,0.80)",marginBottom:12}}>Live fetch from medex.com.bd — Top pharma: Square, Incepta, Beximco, Renata, ACI, Healthcare, Opsonin, Eskayef</div>
        {/* Company group */}
        <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:8}}>
          <div style={{width:28,height:28,borderRadius:9999,background:"#fff",display:"flex",alignItems:"center",justifyContent:"center",fontSize:11,fontWeight:700,color:"#0284C7"}}>SQ</div>
          <span style={{fontSize:14,fontWeight:600,color:"#fff"}}>Square Pharmaceuticals PLC</span>
          <span style={{padding:"2px 8px",borderRadius:9999,background:"rgba(255,255,255,0.10)",fontSize:12,color:"#BAE6FD"}}>12 popular</span>
        </div>
        <div style={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:8}}>
          {[{brand:"Napa",desc:"Tablet 500mg • Paracetamol"},{brand:"Seclo",desc:"Capsule 20mg • Omeprazole"},{brand:"Glucomet",desc:"Tablet 500mg • Metformin"},{brand:"Lopid",desc:"Capsule 300mg • Gemfibrozil"}].map(m=>(
            <div key={m.brand} style={{background:"rgba(255,255,255,0.10)",border:"1px solid rgba(255,255,255,0.20)",borderRadius:12,padding:12,backdropFilter:"blur(4px)"}}>
              <div style={{width:48,height:48,background:"#fff",borderRadius:10,display:"flex",alignItems:"center",justifyContent:"center",fontSize:20,marginBottom:8,padding:4,boxShadow:"0 2px 8px rgba(0,0,0,0.12)"}}>💊</div>
              <div style={{fontSize:12,fontWeight:700,color:"#fff"}}>{m.brand}</div>
              <div style={{fontSize:10,color:"rgba(255,255,255,0.70)"}}>{m.desc}</div>
            </div>
          ))}
        </div>
        <div style={{marginTop:12,fontSize:11,color:"rgba(255,255,255,0.60)"}}>Source: Live from medex.com.bd with real pack images • Updates on each load • 25k medicines DB</div>
      </DarkHero>

      {/* Product cards */}
      {products.map((p,i)=>(
        <Card key={i} style={{marginBottom:8,display:"flex",alignItems:"center",gap:12}}>
          <div style={{width:64,height:64,borderRadius:12,background:"#F8FAFC",border:"1px solid #E2E8F0",display:"flex",alignItems:"center",justifyContent:"center",fontSize:28,flexShrink:0}}>💊</div>
          <div style={{flex:1,minWidth:0}}>
            <div style={{fontSize:12,fontWeight:700,color:"#0F172A"}}>{p.brand}</div>
            <div style={{fontSize:10,color:"#64748B"}}>{p.form}</div>
            <div style={{fontSize:10,color:"#64748B",marginBottom:4}}>{p.generic}</div>
            <div style={{display:"flex",alignItems:"center",gap:4,marginBottom:4}}>
              <CompanyBadge name={p.company} size={16}/><span style={{fontSize:9,color:"#475569"}}>{p.company.length>28?p.company.slice(0,28)+"…":p.company}</span>
            </div>
            <RegPill label="NEML Listed"/>
            <span style={{marginLeft:4}}><StatusPill variant="emerald" style={{fontSize:8,padding:"2px 6px"}}>🛡 DGDA Registered</StatusPill></span>
          </div>
        </Card>
      ))}
    </div>
  );
}

function HubTrips() {
  const watchList = [
    {molecule:"Bedaquiline",class:"Anti-TB",originator:"Johnson & Johnson",level:"CRITICAL",vol:47,delta:14,deltaPct:38,territories:"Dhaka South (9), Chittagong (4)"},
    {molecule:"Sofosbuvir",class:"Antiviral (HCV)",originator:"Gilead Sciences",level:"HIGH",vol:112,delta:-6,deltaPct:-5,territories:"Sylhet (12), Dhaka North (8)"},
    {molecule:"Nintedanib",class:"IPF / Oncology",originator:"Boehringer Ingelheim",level:"HIGH",vol:23,delta:5,deltaPct:28,territories:"Dhaka South (6), Chittagong (2)"},
    {molecule:"Ribociclib",class:"Breast Oncology",originator:"Novartis",level:"MEDIUM",vol:8,delta:0,deltaPct:0,territories:"Dhaka (3)"},
  ];
  const levelStyle: Record<string,string> = {CRITICAL:"red",HIGH:"amber",MEDIUM:"slate",LOW:"slate"};
  return (
    <div>
      <Card style={{marginBottom:12}}>
        <div style={{display:"flex",alignItems:"center",justifyContent:"space-between",flexWrap:"wrap",gap:8,marginBottom:8}}>
          <SectionHeader icon="🌐" title="TRIPS Waiver Portfolio Tracker"/>
          <StatusPill variant="amber">LDC pharma waiver → 2033-01-01</StatusPill>
        </div>
        <div style={{fontSize:11,color:"#64748B",marginBottom:12}}>High-priority generic molecules on the LDC pharmaceutical TRIPS waiver (to 2033) with real field volume from prescription scans — PMD visibility into where watch-list brands are being written and whether volume is rising.</div>
        <div style={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:8,marginBottom:12}}>
          <MiniKPI label="Molecules on watch" value="24"/>
          <MiniKPI label="With field volume (90d)" value="18"/>
          <MiniKPI label="Watch-list items scanned" value="190"/>
          <MiniKPI label="Rising vs prev period" value="+12"/>
        </div>
        <div style={{display:"flex",gap:6}}>
          {["30","90","180"].map(d=>(
            <FilterChip key={d} label={d+" days"} active={d==="90"}/>
          ))}
          <RefreshPill/>
        </div>
      </Card>

      {watchList.map((m,i)=>(
        <Card key={i} style={{marginBottom:8}}>
          <div style={{display:"flex",alignItems:"flex-start",justifyContent:"space-between",marginBottom:6}}>
            <div>
              <div style={{fontSize:13,fontWeight:600,color:"#0F172A"}}>{m.molecule}</div>
              <div style={{fontSize:10,color:"#64748B"}}>{m.class} • {m.originator}</div>
            </div>
            <StatusPill variant={levelStyle[m.level] as keyof typeof variants}>{m.level}</StatusPill>
          </div>
          <div style={{display:"flex",flexWrap:"wrap",gap:12,marginBottom:4}}>
            <div><span style={{fontSize:9,color:"#94A3B8"}}>Window </span><span style={{fontSize:10,color:"#94A3B8"}}>→ 2033-01-01</span></div>
            <div><span style={{fontSize:13,fontWeight:700,color:"#0F172A"}}>{m.vol}</span><span style={{fontSize:9,color:"#94A3B8"}}> items</span></div>
            <div style={{color:m.delta>0?"#059669":m.delta<0?"#DC2626":"#CBD5E1",fontSize:12,fontWeight:600}}>
              {m.delta>0?"▲ +":"▼ "}{m.delta!==0?Math.abs(m.delta):"—"}
              {m.delta!==0&&<span style={{fontSize:9,color:"#94A3B8"}}> ({m.delta>0?"+":""}{m.deltaPct}%)</span>}
            </div>
          </div>
          <div style={{fontSize:10,color:"#64748B"}}>📍 {m.territories}</div>
        </Card>
      ))}
    </div>
  );
}

function HubNews() {
  const featured = {headline:"DGDA Revises Ceiling Price for 52 Essential Medicines Effective 1 November 2026",summary:"Bangladesh Drug Administration has notified a revised MRP ceiling for analgesics, antibiotics, and cardiac medications ahead of Q4 procurement season.",source:"DGDA Regulatory",live:true,date:"Today"};
  const news = [
    {source:"Market Board",headline:"Square Pharmaceuticals Q3 FY2026 Revenue Up 14% YoY",summary:"Bangladesh's largest pharma posted BDT 4.2bn revenue for the July–Sep quarter, driven by exports.",date:"13 Sep",type:"market"},
    {source:"WHO Live",headline:"WHO Releases Updated AWaRe Classification for 2026",summary:"Revised Watch-Reserve antibiotic guidance impacts detailing strategy for broad-spectrum prescriptions.",date:"12 Sep",type:"regulatory",live:true},
    {source:"DGDA",headline:"Import Ban on 3 API Batches — Ciprofloxacin 500mg",summary:"Emergency alert from DGDA: three batch numbers of imported ciprofloxacin recalled for GMP non-compliance.",date:"11 Sep",type:"regulatory"},
  ];
  return (
    <div>
      <Card style={{marginBottom:12}}>
        <div style={{display:"flex",alignItems:"center",justifyContent:"space-between",marginBottom:8}}>
          <SectionHeader icon="📰" title="Industry News & Market Trends"/>
          <RefreshPill/>
        </div>
        <div style={{fontSize:11,color:"#64748B",marginBottom:12}}>Medex market board, DGDA regulatory desk, WHO live RSS & Bangladeshi pharma reports.</div>
        {/* Featured */}
        <div style={{fontSize:10,fontWeight:700,color:"#94A3B8",textTransform:"uppercase",letterSpacing:"0.1em",marginBottom:6}}>★ Featured story</div>
        <div style={{background:"linear-gradient(135deg,#0F172A,#1E40AF)",borderRadius:16,padding:20,marginBottom:16}}>
          <div style={{display:"flex",alignItems:"center",gap:6,marginBottom:8}}>
            <StatusPill variant="amber">Regulatory</StatusPill>
            {featured.live && <StatusPill variant="emerald">LIVE</StatusPill>}
            <span style={{marginLeft:"auto",fontSize:10,color:"rgba(255,255,255,0.60)"}}>{featured.date}</span>
          </div>
          <div style={{fontSize:14,fontWeight:700,color:"#fff",marginBottom:6}}>{featured.headline}</div>
          <div style={{fontSize:11,color:"rgba(255,255,255,0.80)",marginBottom:10,lineHeight:1.5}}>{featured.summary}</div>
          <div style={{display:"flex",alignItems:"center",gap:4,fontSize:11,color:"#93C5FD",cursor:"pointer"}}>Open source ↗</div>
        </div>
        {/* Timeline */}
        <div style={{fontSize:10,fontWeight:700,color:"#64748B",textTransform:"uppercase",letterSpacing:"0.1em",marginBottom:10}}>📊 Market place timeline</div>
        {news.map((n,i)=>(
          <div key={i} style={{background:"#fff",border:"1px solid #E2E8F0",borderRadius:12,padding:12,marginBottom:8,borderLeft:`4px solid ${n.type==="regulatory"?"#FBBF24":"#60A5FA"}`}}>
            <div style={{display:"flex",alignItems:"center",gap:6,marginBottom:4}}>
              <span style={{fontSize:9,fontWeight:700,color:"#64748B",textTransform:"uppercase"}}>{n.source}</span>
              {n.live&&<StatusPill variant="emerald" style={{fontSize:9,padding:"1px 6px"}}>LIVE</StatusPill>}
              <span style={{marginLeft:"auto",fontSize:9,color:"#94A3B8"}}>{n.date}</span>
            </div>
            <div style={{fontSize:12,fontWeight:600,color:"#0F172A",marginBottom:3}}>{n.headline}</div>
            <div style={{fontSize:10,color:"#64748B",marginBottom:6,lineHeight:1.5,display:"-webkit-box",WebkitLineClamp:2,WebkitBoxOrient:"vertical",overflow:"hidden"}}>{n.summary}</div>
            <span style={{fontSize:10,color:"#1D4ED8",cursor:"pointer"}}>Open source ↗</span>
          </div>
        ))}
      </Card>
    </div>
  );
}

function HubJobs() {
  const jobs = [
    {title:"Senior Medical Promotion Officer",company:"Square Pharmaceuticals PLC",category:"MPO",location:"Dhaka • 2-4 yrs",desc:"Manage prescription audit, doctor detailing, and field intelligence for the Cardiology portfolio across Dhaka South territory.",tags:["Cardiology","Detailing","CRM"],salary:"BDT 45,000 – 55,000/mo",posted:"Posted today"},
    {title:"Territory Manager — Chittagong",company:"Incepta Pharmaceuticals Ltd.",category:"RSO-RSM",location:"Chittagong • 5-7 yrs",desc:"Lead a 12-member MPO team, set monthly targets, and oversee competitive SoV tracking for the Chittagong division.",tags:["Leadership","SoV","Sales Ops"],salary:"BDT 80,000 – 95,000/mo",posted:"Posted 11 Sep"},
    {title:"Product Manager — Gastroenterology",company:"Renata Limited",category:"Product Management",location:"Dhaka • 4-6 yrs",desc:"Drive brand strategy, MRP positioning and doctor detailing materials for the GI portfolio.",tags:["Brand Strategy","MRP","GI"],salary:"BDT 90,000 – 120,000/mo",posted:"Posted 12 Sep"},
  ];
  return (
    <div>
      <DarkHero style={{marginBottom:16}}>
        <div style={{fontSize:14,fontWeight:600,color:"#fff",marginBottom:4}}>💼 Health & Pharma Job Board</div>
        <div style={{fontSize:11,color:"rgba(255,255,255,0.70)",marginBottom:12}}>Curated MPO, Senior Territory Manager, Executive Product Management (PMD) & Regulatory Affairs vacancies with direct apply links.</div>
        <div style={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:8}}>
          <MiniKPI label="Open roles" value="38" dark/>
          <MiniKPI label="Fresh today" value="7" dark/>
        </div>
      </DarkHero>
      <Card style={{marginBottom:12}}>
        <div style={{display:"flex",flexWrap:"wrap",gap:8,marginBottom:8}}>
          <select style={{flex:1,minWidth:120,height:36,borderRadius:12,border:"1px solid #E2E8F0",padding:"0 10px",fontSize:11,fontFamily:"Inter,sans-serif",outline:"none",background:"#fff"}}>
            <option>All roles</option><option>MPO</option><option>RSO-RSM</option><option>Product Management</option><option>Sales Ops</option><option>Regulatory Affairs</option>
          </select>
          <select style={{flex:1,minWidth:100,height:36,borderRadius:12,border:"1px solid #E2E8F0",padding:"0 10px",fontSize:11,fontFamily:"Inter,sans-serif",outline:"none",background:"#fff"}}>
            <option>All departments</option>
          </select>
          <input placeholder="Company, city, keyword..." style={{flex:2,minWidth:140,height:36,borderRadius:12,border:"1px solid #E2E8F0",padding:"0 12px",fontSize:11,fontFamily:"Inter,sans-serif",outline:"none"}}/>
        </div>
      </Card>
      {jobs.map((j,i)=>(
        <Card key={i} style={{marginBottom:8}}>
          <div style={{display:"flex",alignItems:"flex-start",justifyContent:"space-between",marginBottom:4}}>
            <div style={{fontSize:12,fontWeight:700,color:"#0F172A",flex:1}}>{j.title}</div>
            <StatusPill variant="blue" style={{fontSize:10,flexShrink:0,marginLeft:8}}>{j.category}</StatusPill>
          </div>
          <div style={{fontSize:11,color:"#475569",marginBottom:4}}>{j.company}</div>
          <div style={{fontSize:11,color:"#64748B",marginBottom:6,display:"flex",alignItems:"center",gap:4}}>📍 {j.location}</div>
          <div style={{fontSize:11,color:"#475569",marginBottom:8,lineHeight:1.5,display:"-webkit-box",WebkitLineClamp:2,WebkitBoxOrient:"vertical",overflow:"hidden"}}>{j.desc}</div>
          <div style={{display:"flex",flexWrap:"wrap",gap:4,marginBottom:8}}>
            {j.tags.map(t=><StatusPill key={t} variant="slate" style={{fontSize:9}}>{t}</StatusPill>)}
          </div>
          <div style={{borderTop:"1px solid #F1F5F9",paddingTop:8,display:"flex",alignItems:"center",justifyContent:"space-between"}}>
            <span style={{fontSize:10,color:"#64748B"}}>{j.salary}</span>
            <span style={{fontSize:10,color:j.posted==="Posted today"?"#047857":"#64748B",fontWeight:j.posted==="Posted today"?600:400}}>{j.posted}</span>
          </div>
          <PrimaryBtn icon="✈" style={{width:"100%",justifyContent:"center",marginTop:8,fontSize:11}}>Apply now</PrimaryBtn>
        </Card>
      ))}
    </div>
  );
}

function HubHealthDays() {
  const [month,setMonth] = useState("November 2026");
  const nextDay = {name:"World Diabetes Day",date:"14 Nov",accent:"#1E40AF",specialties:["Endocrinology","Medicine","Cardiology"],daysOut:23};
  const days = [
    {day:1,name:"World Pneumonia Day",color:"#1D4ED8"},
    {day:2,name:null},{day:3,name:null},
    {day:5,name:"World Cancer Day",color:"#BE185D"},
    {day:10,name:"World COPD Day",color:"#0E7490"},
    {day:14,name:"World Diabetes Day",color:"#1E40AF",today:false},
    {day:17,name:"World Prematurity Day",color:"#7C3AED"},
    {day:20,name:"World Children's Day",color:"#059669"},
  ];
  return (
    <div>
      {/* Hero */}
      <div style={{background:"linear-gradient(90deg,#1D4ED8,#0F172A)",borderRadius:16,padding:24,marginBottom:16,position:"relative",overflow:"hidden"}}>
        <div style={{position:"absolute",top:-60,right:-60,width:200,height:200,borderRadius:"50%",background:"rgba(255,255,255,0.08)",filter:"blur(30px)"}}/>
        <div style={{fontSize:11,color:"rgba(255,255,255,0.70)",textTransform:"uppercase",letterSpacing:"0.1em",marginBottom:6}}>Next campaign window</div>
        <div style={{fontSize:18,fontWeight:700,color:"#fff",marginBottom:8}}>{nextDay.name} — {nextDay.date}</div>
        <div style={{fontSize:13,color:"rgba(255,255,255,0.85)",marginBottom:10,lineHeight:1.5}}>Plan glucometer camps, metformin/SGLT2/DPP4 detailing, and diet leaflets 3 weeks ahead. Highest MPO activity day after Heart Day.</div>
        <div style={{fontSize:11,color:"#BFDBFE"}}>{nextDay.daysOut} days out • Focus: {nextDay.specialties.join(", ")}</div>
      </div>

      {/* Calendar card */}
      <Card>
        <div style={{marginBottom:12}}>
          <div style={{fontSize:14,fontWeight:600,color:"#0F172A",marginBottom:4}}>International Health Days Calendar</div>
          <div style={{fontSize:11,color:"#64748B",marginBottom:12}}>Click any WHO / global health day to pull a pre-generated promotional script & campaign brand focus for your doctor visits.</div>
          {/* Month stepper */}
          <div style={{display:"flex",alignItems:"center",justifyContent:"space-between",marginBottom:12}}>
            <button style={{width:32,height:32,borderRadius:9999,border:"1px solid #E2E8F0",background:"#fff",cursor:"pointer",fontSize:14}}>‹</button>
            <span style={{fontSize:12,fontWeight:700,color:"#0F172A"}}>{month}</span>
            <button onClick={()=>setMonth("December 2026")} style={{width:32,height:32,borderRadius:9999,border:"1px solid #E2E8F0",background:"#fff",cursor:"pointer",fontSize:14}}>›</button>
          </div>
          {/* Weekday header */}
          <div style={{display:"grid",gridTemplateColumns:"repeat(7,1fr)",gap:4,marginBottom:4}}>
            {["Sun","Mon","Tue","Wed","Thu","Fri","Sat"].map(d=>(
              <div key={d} style={{fontSize:10,fontWeight:700,color:"#94A3B8",textTransform:"uppercase",textAlign:"center",padding:"4px 0"}}>{d}</div>
            ))}
          </div>
          {/* Calendar grid — November starts on Sunday */}
          <div style={{display:"grid",gridTemplateColumns:"repeat(7,1fr)",gap:4}}>
            {Array.from({length:35}).map((_,i)=>{
              const d = i+1; // Nov starts on Sunday offset 0
              const dayNum = d;
              if(dayNum>30) return <div key={i} style={{height:74,borderRadius:12,background:"#F8FAFC",border:"1px solid #F1F5F9"}}/>;
              const hd = days.find(dd=>dd.day===dayNum);
              const isToday = dayNum===13;
              return (
                <div key={i} style={{height:74,borderRadius:12,border:`1px solid ${isToday?"#60A5FA":hd?"#E2E8F0":"#F1F5F9"}`,background:isToday?"#EFF6FF":"#fff",borderTop:hd?`3px solid ${hd.color}`:"1px solid #F1F5F9",padding:"6px 4px",cursor:hd?"pointer":"default",display:"flex",flexDirection:"column",gap:2}}>
                  <div style={{fontSize:10,fontWeight:700,color:isToday?"#1E40AF":"#94A3B8"}}>{dayNum}</div>
                  {hd?.name && <div style={{fontSize:8,fontWeight:600,color:"#0F172A",lineHeight:1.2,overflow:"hidden",display:"-webkit-box",WebkitLineClamp:2,WebkitBoxOrient:"vertical"}}>{hd.name}</div>}
                  {hd && <div style={{fontSize:8,color:"#2563EB",marginTop:"auto"}}>✨ Campaign</div>}
                </div>
              );
            })}
          </div>
        </div>

        {/* Month chip list */}
        <div style={{borderTop:"1px solid #F1F5F9",paddingTop:12}}>
          <div style={{display:"flex",flexWrap:"wrap",gap:6}}>
            {days.filter(d=>d.name).map(d=>(
              <button key={d.day} style={{padding:"4px 10px",borderRadius:9999,border:"1px solid #E2E8F0",background:d.day===13?"#EFF6FF":"#fff",color:d.day===13?"#1E40AF":"#475569",fontSize:10,fontWeight:600,cursor:"pointer"}}>
                {d.name} • {d.day<10?"0"+d.day:d.day} Nov
              </button>
            ))}
          </div>
        </div>
      </Card>
    </div>
  );
}

function HubScreen() {
  const [tab,setTab] = useState(0);
  return (
    <div>
      <Card style={{marginBottom:12}}>
        <div style={{fontSize:18,fontWeight:700,color:"#0F172A",marginBottom:4}}>Pharma Intelligence Hub</div>
        <div style={{fontSize:11,color:"#64748B"}}>Real-time Medex market intelligence, DGDA notifications, industry jobs & WHO health campaigns</div>
      </Card>
      {/* Segmented tab bar */}
      <div style={{background:"#F1F5F9",borderRadius:12,padding:4,display:"flex",gap:2,marginBottom:16,overflowX:"auto"}}>
        {hubTabs.map((t,i)=>(
          <button key={i} onClick={()=>setTab(i)} style={{flex:"0 0 auto",padding:"7px 12px",borderRadius:10,border:"none",background:tab===i?"#fff":"transparent",color:tab===i?"#0F172A":"#475569",fontSize:11,fontWeight:600,cursor:"pointer",boxShadow:tab===i?"0 1px 4px rgba(0,0,0,0.08)":"none",whiteSpace:"nowrap"}}>
            {t}
          </button>
        ))}
      </div>
      {tab===0 && <HubDrugIndex/>}
      {tab===1 && <HubTrips/>}
      {tab===2 && <HubNews/>}
      {tab===3 && <HubJobs/>}
      {tab===4 && <HubHealthDays/>}
    </div>
  );
}

// ═══════════════════════════════════════════════════════════
// TEAM / RSM SCREENS
// ═══════════════════════════════════════════════════════════
function TeamMap() {
  const [mode,setMode] = useState("SoV");
  return (
    <Card style={{marginBottom:16}}>
      <SectionHeader icon="🗺" title="Territory Penetration Heatmap" sub="Live scan locations mapped across Bangladesh — bubble size = volume, colour = your company's market penetration"/>
      <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:12}}>
        <div style={{display:"flex",gap:2,background:"#F1F5F9",borderRadius:8,padding:3}}>
          {["SoV","Density clusters"].map(m=>(
            <button key={m} onClick={()=>setMode(m)} style={{padding:"5px 10px",borderRadius:6,border:"none",background:mode===m?"#0F172A":"transparent",color:mode===m?"#fff":"#475569",fontSize:11,fontWeight:600,cursor:"pointer"}}>
              {m}
            </button>
          ))}
        </div>
        <select style={{height:32,borderRadius:8,border:"1px solid #E2E8F0",padding:"0 8px",fontSize:11,fontFamily:"Inter,sans-serif",background:"#fff",outline:"none"}}>
          <option>30 days</option><option>7 days</option><option>90 days</option>
        </select>
        <button style={{width:32,height:32,borderRadius:8,border:"1px solid #E2E8F0",background:"#fff",cursor:"pointer",fontSize:12}}>🔄</button>
      </div>
      {/* Map stats */}
      <div style={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:8,marginBottom:12}}>
        <MiniKPI label="Districts covered" value="18"/>
        <MiniKPI label="Total items" value="2,841"/>
        <MiniKPI label="Market penetration" value="38%"/>
        <MiniKPI label="Own vs Comp-heavy" value="12 / 6"/>
      </div>
      {/* Map placeholder */}
      <div style={{height:280,background:"#F8FAFC",border:"1px solid #E2E8F0",borderRadius:12,position:"relative",overflow:"hidden",marginBottom:12}}>
        {/* Bangladesh outline suggestion */}
        <div style={{position:"absolute",inset:0,display:"flex",alignItems:"center",justifyContent:"center",flexDirection:"column",gap:8}}>
          <div style={{fontSize:48,opacity:0.15}}>🗺</div>
          <div style={{fontSize:10,color:"#CBD5E1"}}>Bangladesh Territory Map</div>
        </div>
        {/* Bubble markers */}
        {mode==="SoV" ? <>
          <div style={{position:"absolute",top:"30%",left:"48%",width:40,height:40,borderRadius:"50%",background:"rgba(16,185,129,0.5)",border:"2px solid #10B981",display:"flex",alignItems:"center",justifyContent:"center",fontSize:9,fontWeight:700,color:"#fff"}}>214</div>
          <div style={{position:"absolute",top:"45%",left:"55%",width:24,height:24,borderRadius:"50%",background:"rgba(245,158,11,0.5)",border:"2px solid #F59E0B"}}/>
          <div style={{position:"absolute",top:"60%",left:"38%",width:32,height:32,borderRadius:"50%",background:"rgba(239,68,68,0.5)",border:"2px solid #EF4444"}}/>
          {/* Popup */}
          <div style={{position:"absolute",top:"8%",left:"50%",background:"#fff",borderRadius:10,padding:"8px 10px",boxShadow:"0 4px 12px rgba(0,0,0,0.12)",fontSize:10,minWidth:130,border:"1px solid #E2E8F0"}}>
            <div style={{fontWeight:700,color:"#0F172A",marginBottom:2}}>Dhaka South</div>
            <div style={{color:"#64748B"}}>Items: 214 (own 128 / comp 86)</div>
            <div style={{color:"#64748B"}}>SoV: 60%</div>
            <div style={{color:"#64748B"}}>Rx: 41</div>
          </div>
        </> : <>
          <div style={{position:"absolute",top:"30%",left:"48%",width:44,height:44,borderRadius:"50%",background:"rgba(124,58,237,0.5)",border:"2px solid #7C3AED",display:"flex",alignItems:"center",justifyContent:"center",fontSize:10,fontWeight:700,color:"#fff"}}>12</div>
          <div style={{position:"absolute",top:"50%",left:"53%",width:30,height:30,borderRadius:"50%",background:"rgba(2,132,199,0.5)",border:"2px solid #0284C7",display:"flex",alignItems:"center",justifyContent:"center",fontSize:9,fontWeight:700,color:"#fff"}}>7</div>
          <div style={{position:"absolute",top:"62%",left:"36%",width:22,height:22,borderRadius:"50%",background:"rgba(16,185,129,0.5)",border:"2px solid #10B981",display:"flex",alignItems:"center",justifyContent:"center",fontSize:9,fontWeight:700,color:"#fff"}}>3</div>
        </>}
      </div>
      {/* Legend */}
      <div style={{display:"flex",flexWrap:"wrap",gap:8,fontSize:10,color:"#64748B"}}>
        {mode==="SoV" ? <>
          <span>🟢 Own-company dominant</span>
          <span>🟡 Competitive / mixed</span>
          <span>🔴 Competitor-heavy</span>
        </> : <>
          <span style={{color:"#7C3AED"}}>● ≥10 scans</span>
          <span style={{color:"#0284C7"}}>● 5–9</span>
          <span style={{color:"#10B981"}}>● 2–4</span>
          <span style={{color:"#94A3B8"}}>● 1</span>
        </>}
        <span style={{marginLeft:"auto"}}>Bubble size = prescription volume</span>
      </div>
    </Card>
  );
}

function TeamTiers() {
  const tiers = [
    {tier:"A",name:"Dr. A. K. M. Rahman",spec:"Cardiology",territory:"Dhaka South",rx:18,items:72,own:12,comp:6,sov:67,atRisk:false},
    {tier:"A",name:"Dr. Salma Begum",spec:"Gastroenterology",territory:"Gulshan",rx:14,items:56,own:9,comp:5,sov:64,atRisk:false},
    {tier:"B",name:"Dr. Karim Uddin",spec:"Medicine",territory:"Motijheel",rx:9,items:36,own:4,comp:5,sov:44,atRisk:false},
    {tier:"B",name:"Dr. Farida Haque",spec:"Cardiology",territory:"Dhanmondi",rx:7,items:28,own:2,comp:5,sov:29,atRisk:true},
    {tier:"C",name:"Dr. Babul Islam",spec:"Orthopedics",territory:"Mirpur",rx:3,items:12,own:1,comp:2,sov:33,atRisk:false},
  ];
  const [tierFilter,setTierFilter] = useState("All");
  const filtered = tierFilter==="All"?tiers:tiers.filter(d=>d.tier===tierFilter);
  const tierColors: Record<string,string> = {A:"#7C3AED",B:"#1D4ED8",C:"#475569"};

  return (
    <Card style={{marginBottom:16}}>
      <SectionHeader icon="👨‍⚕️" title="Doctor Prescribing Tiering Matrix (A/B/C)" sub="Auto-classified from audit volume — Tier A >10 Rx, B 4–9 Rx, C ≤3 Rx/month. At-risk = high-volume doctors with low own-brand share."/>
      <div style={{display:"flex",gap:6,marginBottom:12,flexWrap:"wrap"}}>
        {["All","A","B","C"].map(t=>(
          <FilterChip key={t} label={t==="All"?"All":`Tier ${t}`} active={tierFilter===t} onClick={()=>setTierFilter(t)}/>
        ))}
        <select style={{height:32,borderRadius:8,border:"1px solid #E2E8F0",padding:"0 8px",fontSize:11,fontFamily:"Inter,sans-serif",background:"#fff",outline:"none"}}>
          <option>All specialties</option>
        </select>
      </div>
      <div style={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:8,marginBottom:12}}>
        <div style={{background:"#F5F3FF",border:"1px solid #DDD6FE",borderRadius:10,padding:10}}>
          <div style={{fontSize:10,fontWeight:700,color:"#7C3AED",marginBottom:2}}>Tier A · High prescriber</div>
          <div style={{fontSize:18,fontWeight:700,color:"#6D28D9"}}>2</div>
        </div>
        <div style={{background:"#EFF6FF",border:"1px solid #DBEAFE",borderRadius:10,padding:10}}>
          <div style={{fontSize:10,fontWeight:700,color:"#1D4ED8",marginBottom:2}}>Tier B · Medium</div>
          <div style={{fontSize:18,fontWeight:700,color:"#1E40AF"}}>2</div>
        </div>
        <div style={{background:"#F1F5F9",border:"1px solid #E2E8F0",borderRadius:10,padding:10}}>
          <div style={{fontSize:10,fontWeight:700,color:"#475569",marginBottom:2}}>Tier C · Occasional</div>
          <div style={{fontSize:18,fontWeight:700,color:"#334155"}}>1</div>
        </div>
        <div style={{background:"#FEE2E2",border:"1px solid #FECACA",borderRadius:10,padding:10}}>
          <div style={{fontSize:10,fontWeight:700,color:"#DC2626",marginBottom:2}}>At-risk switchers</div>
          <div style={{fontSize:18,fontWeight:700,color:"#B91C1C"}}>1</div>
        </div>
      </div>

      {filtered.map((d,i)=>(
        <div key={i} style={{border:"1px solid #F1F5F9",borderRadius:12,padding:12,marginBottom:8}}>
          <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:6}}>
            <span style={{padding:"3px 8px",borderRadius:6,background:`${tierColors[d.tier]}20`,color:tierColors[d.tier],fontSize:10,fontWeight:700}}>Tier {d.tier}</span>
            <span style={{fontSize:13,fontWeight:500,color:"#0F172A"}}>{d.name}</span>
            {d.atRisk && <RegPill label="Duplicate Rx Detected" icon="⚠"/>}
          </div>
          <div style={{fontSize:10,color:"#64748B",marginBottom:8}}>{d.spec} · {d.territory}</div>
          <div style={{display:"flex",gap:12,marginBottom:8,flexWrap:"wrap"}}>
            <span style={{fontSize:11,color:"#475569"}}>{d.rx} Rx</span>
            <span style={{fontSize:11,color:"#475569"}}>{d.items} items</span>
            <span style={{fontSize:11,color:"#047857",fontWeight:600}}>{d.own} own</span>
            <span style={{fontSize:11,color:"#B45309"}}>{d.comp} comp</span>
          </div>
          <div style={{display:"flex",alignItems:"center",gap:8}}>
            <span style={{fontSize:12,fontWeight:700,color:"#0F172A",flexShrink:0}}>{d.sov}%</span>
            <ProgressBar pct={d.sov}/>
          </div>
          {d.atRisk && (
            <div style={{marginTop:6}}>
              <StatusPill variant="red">⚠ At risk</StatusPill>
            </div>
          )}
        </div>
      ))}
    </Card>
  );
}

function TeamLeaderboard() {
  const mpos = [
    {rank:1,name:"MR001 Rahim",role:"MPO",territory:"Dhaka South",rx:38,items:142,own:28,comp:14,sov:66,wow:8},
    {rank:2,name:"MR002 Karim",role:"MPO",territory:"Gulshan",rx:31,items:116,own:22,comp:9,sov:71,wow:3},
    {rank:3,name:"MR003 Rahela",role:"RSO",territory:"Chittagong Metro",rx:44,items:168,own:30,comp:14,sov:68,wow:-2},
    {rank:4,name:"MR004 Bashir",role:"MPO",territory:"Sylhet",rx:22,items:84,own:14,comp:8,sov:64,wow:-5},
  ];
  return (
    <Card style={{marginBottom:16}}>
      <div style={{display:"flex",alignItems:"center",justifyContent:"space-between",marginBottom:12}}>
        <SectionHeader icon="🗂" title="Team Leaderboard"/>
        <select style={{height:32,borderRadius:8,border:"1px solid #E2E8F0",padding:"0 8px",fontSize:11,fontFamily:"Inter,sans-serif",background:"#fff",outline:"none"}}>
          <option>30 days</option><option>7 days</option><option>90 days</option>
        </select>
      </div>
      {mpos.map((m,i)=>(
        <div key={i} style={{border:"1px solid #F1F5F9",borderRadius:12,padding:12,marginBottom:8}}>
          <div style={{display:"flex",alignItems:"center",gap:10,marginBottom:6}}>
            <div style={{width:28,height:28,borderRadius:9999,background:"#F1F5F9",display:"flex",alignItems:"center",justifyContent:"center",fontSize:11,fontWeight:700,color:"#0F172A",flexShrink:0}}>{m.rank}</div>
            <div style={{flex:1}}>
              <div style={{fontSize:13,fontWeight:500,color:"#0F172A"}}>{m.name}</div>
              <div style={{fontSize:10,color:"#64748B"}}>{m.role} · {m.territory}</div>
            </div>
            <div style={{textAlign:"right",flexShrink:0}}>
              <div style={{fontSize:11,fontWeight:600,color:m.wow>=0?"#059669":"#DC2626"}}>{m.wow>=0?"▲":"▼"} {Math.abs(m.wow)}% WoW</div>
            </div>
          </div>
          <div style={{display:"flex",gap:12,flexWrap:"wrap",marginBottom:8}}>
            <span style={{fontSize:11,color:"#475569"}}>{m.rx} Rx · {m.items} items</span>
            <span style={{fontSize:11,color:"#047857",fontWeight:600}}>{m.own} own</span>
            <span style={{fontSize:11,color:"#B45309"}}>{m.comp} comp</span>
          </div>
          <div style={{display:"flex",alignItems:"center",gap:8}}>
            <div style={{flex:1}}><ProgressBar pct={m.sov}/></div>
            <span style={{fontSize:12,fontWeight:700,color:"#0F172A",flexShrink:0}}>{m.sov}%</span>
            {/* Sparkline */}
            <svg width="72" height="22" viewBox="0 0 72 22">
              <polyline points="0,18 12,14 24,16 36,10 48,12 60,7 72,9" fill="none" stroke="#059669" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
          </div>
        </div>
      ))}
    </Card>
  );
}

function TeamTargets() {
  const targets = [
    {mpo:"MR001 Rahim",doctor:"Dr. A. K. M. Rahman",spec:"Cardiology",target:4,visits:3,last:"2026-09-10"},
    {mpo:"MR002 Karim",doctor:"Dr. Salma Begum",spec:"Gastroenterology",target:6,visits:6,last:"2026-09-12"},
    {mpo:"MR001 Rahim",doctor:"Dr. Karim Uddin",spec:"Medicine",target:4,visits:1,last:"2026-08-28"},
  ];
  return (
    <Card style={{marginBottom:16}}>
      <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:6}}>
        <span style={{color:"#DC2626"}}>🎯</span>
        <span style={{fontSize:14,fontWeight:600,color:"#0F172A"}}>Doctor Detailing Target Tracker</span>
        <RefreshPill/>
      </div>
      <div style={{fontSize:11,color:"#64748B",marginBottom:12}}>Attach target doctor lists to MPOs. Every scanned prescription whose doctor matches a target auto-logs a visit — duplicates of the same physical Rx never count twice.</div>

      {/* Attach form */}
      <div style={{background:"#F8FAFC",borderRadius:12,padding:12,marginBottom:16}}>
        <div style={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:8,marginBottom:8}}>
          <div>
            <div style={{fontSize:10,fontWeight:600,color:"#64748B",marginBottom:3}}>MPO</div>
            <select style={{width:"100%",height:36,borderRadius:10,border:"1px solid #E2E8F0",padding:"0 8px",fontSize:11,fontFamily:"Inter,sans-serif",background:"#fff",outline:"none"}}>
              <option>MR001 Rahim</option><option>MR002 Karim</option>
            </select>
          </div>
          <div>
            <div style={{fontSize:10,fontWeight:600,color:"#64748B",marginBottom:3}}>Target doctor</div>
            <input placeholder="Dr. A. K. M. Rahman" style={{width:"100%",height:36,borderRadius:10,border:"1px solid #E2E8F0",padding:"0 8px",fontSize:11,fontFamily:"Inter,sans-serif",outline:"none",boxSizing:"border-box"}}/>
          </div>
          <div>
            <div style={{fontSize:10,fontWeight:600,color:"#64748B",marginBottom:3}}>Specialty</div>
            <input placeholder="Orthopedics" style={{width:"100%",height:36,borderRadius:10,border:"1px solid #E2E8F0",padding:"0 8px",fontSize:11,fontFamily:"Inter,sans-serif",outline:"none",boxSizing:"border-box"}}/>
          </div>
          <div>
            <div style={{fontSize:10,fontWeight:600,color:"#64748B",marginBottom:3}}>Monthly visits target</div>
            <input type="number" defaultValue="4" style={{width:"100%",height:36,borderRadius:10,border:"1px solid #E2E8F0",padding:"0 8px",fontSize:11,fontFamily:"Inter,sans-serif",outline:"none",boxSizing:"border-box"}}/>
          </div>
        </div>
        <PrimaryBtn icon="＋" style={{width:"100%",justifyContent:"center",fontSize:11}}>Attach target</PrimaryBtn>
      </div>

      {/* Target list */}
      {targets.map((t,i)=>{
        const pct = Math.round(t.visits/t.target*100);
        return (
          <div key={i} style={{border:"1px solid #F1F5F9",borderRadius:12,padding:12,marginBottom:8}}>
            <div style={{display:"flex",alignItems:"center",justifyContent:"space-between",marginBottom:4}}>
              <span style={{fontSize:13,fontWeight:500,color:"#0F172A"}}>{t.doctor}</span>
              <button style={{width:28,height:28,borderRadius:6,border:"1px solid #CBD5E1",background:"#fff",cursor:"pointer",fontSize:12,color:"#CBD5E1"}}>🗑</button>
            </div>
            <div style={{fontSize:10,color:"#64748B",marginBottom:6}}>{t.mpo} · {t.spec}</div>
            <div style={{display:"flex",gap:12,marginBottom:6}}>
              <span style={{fontSize:11,color:"#475569"}}>Target {t.target}</span>
              <span style={{fontSize:11,fontWeight:600,color:t.visits>=t.target?"#047857":"#475569"}}>Visits {t.visits} / {t.target}</span>
            </div>
            <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:4}}>
              <div style={{flex:1}}><ProgressBar pct={pct} thick/></div>
              <span style={{fontSize:9,color:"#94A3B8",flexShrink:0}}>{pct}%</span>
            </div>
            <div style={{fontSize:10,color:"#64748B"}}>Last visit: {t.last}</div>
          </div>
        );
      })}

      {/* Auto-visit log */}
      <div style={{marginTop:12}}>
        <div style={{fontSize:10,fontWeight:700,color:"#64748B",textTransform:"uppercase",letterSpacing:"0.08em",marginBottom:8,display:"flex",alignItems:"center",gap:4}}>🛣 Auto-visit log (from prescription scans)</div>
        {[
          {doctor:"Dr. A. K. M. Rahman",mpo:"MR001",time:"2026-09-13 10:34",rx:"Rx #A-128"},
          {doctor:"Dr. Salma Begum",mpo:"MR002",time:"2026-09-12 14:21",rx:"Rx #A-124"},
        ].map((v,i)=>(
          <div key={i} style={{background:"#F8FAFC",border:"1px solid #F1F5F9",borderRadius:8,padding:"8px 10px",marginBottom:6,display:"flex",alignItems:"center",gap:8}}>
            <div style={{width:6,height:6,borderRadius:"50%",background:"#059669",flexShrink:0}}/>
            <span style={{fontSize:11,fontWeight:600,color:"#0F172A"}}>{v.doctor}</span>
            <span style={{fontSize:11,color:"#64748B"}}>visited by {v.mpo}</span>
            <span style={{marginLeft:"auto",fontSize:10,color:"#94A3B8"}}>{v.time}</span>
            <span style={{fontSize:10,color:"#2563EB",cursor:"pointer",textDecoration:"underline"}}>{v.rx}</span>
          </div>
        ))}
        {false && <EmptyState icon="🛣" text="No auto-logged visits yet."/>}
      </div>
    </Card>
  );
}

function TeamOffTerritory() {
  const flags = [
    {doctor:"Dr. Sabbir Ahmed",territory:"Dhaka South",actual:"Khulna Sadar, Khulna",mpo:"MR001",date:"2026-09-02 14:11",rx:"Rx #A-109",duplicate:true},
    {doctor:"Dr. Mina Khatun",territory:"Chittagong Metro",actual:"Sylhet Sadar, Sylhet",mpo:"MR003",date:"2026-09-01 09:22",rx:"Rx #A-103",duplicate:false},
  ];
  return (
    <Card style={{marginBottom:16}}>
      <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:6,flexWrap:"wrap"}}>
        <span style={{color:"#DC2626"}}>🎯</span>
        <span style={{fontSize:14,fontWeight:600,color:"#0F172A"}}>Off-Territory Audit Verification</span>
        <StatusPill variant="red">{flags.length} flagged</StatusPill>
        <RefreshPill/>
      </div>
      <div style={{fontSize:11,color:"#64748B",marginBottom:12}}>GPS-pinned and location-resolved audits are geofenced against each MPO's assigned territory — Off-Territory Audit uploads are listed here alongside the pHash duplicate guard.</div>
      {flags.map((f,i)=>(
        <div key={i} style={{background:"rgba(254,242,242,0.60)",border:"1px solid #FECACA",borderRadius:12,padding:10,marginBottom:8,display:"flex",gap:10}}>
          <div style={{width:36,height:36,borderRadius:8,background:"#fff",border:"1px solid #FECACA",display:"flex",alignItems:"center",justifyContent:"center",fontSize:14,flexShrink:0}}>📋</div>
          <div style={{flex:1,minWidth:0}}>
            <div style={{display:"flex",alignItems:"center",gap:4,flexWrap:"wrap",marginBottom:3}}>
              <span style={{fontSize:12,fontWeight:600,color:"#0F172A"}}>{f.doctor}</span>
              <RegPill label="Off-Territory Audit" icon="📍"/>
              {f.duplicate && <RegPill label="also Duplicate"/>}
            </div>
            <div style={{fontSize:11,color:"#475569",marginBottom:2}}>Assigned: {f.territory} · Scanned in: {f.actual}</div>
            <div style={{fontSize:10,color:"#94A3B8",marginBottom:4}}>MR {f.mpo} · {f.date}</div>
            <span style={{fontSize:10,color:"#2563EB",cursor:"pointer",textDecoration:"underline"}}>Review Rx</span>
          </div>
        </div>
      ))}
    </Card>
  );
}

function TeamStewardship() {
  const chambers = [
    {doctor:"Dr. A. K. M. Rahman",chamber:"Ibn Sina Dhanmondi",spec:"Cardiology",district:"Dhaka",rxAudited:12,abxItems:4,broad:1,sharePct:33,brands:"Azithromycin, Cefixime"},
    {doctor:"Dr. Karim Uddin",chamber:"Private Chamber Motijheel",spec:"Medicine",district:"Dhaka",rxAudited:9,abxItems:6,broad:2,sharePct:67,brands:"Ciprofloxacin, Moxifloxacin, Amoxicillin"},
    {doctor:"Dr. Farida Haque",chamber:"Dhanmondi Clinic",spec:"Cardiology",district:"Dhaka",rxAudited:7,abxItems:0,broad:0,sharePct:0,brands:"—"},
  ];
  const shareChipVariant = (pct:number) => pct>=40?"red":pct>=20?"amber":"slate";
  return (
    <Card style={{marginBottom:16}}>
      <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:6,flexWrap:"wrap"}}>
        <span style={{color:"#DC2626"}}>🦠</span>
        <span style={{fontSize:14,fontWeight:600,color:"#0F172A"}}>Antibiotic Stewardship Monitor</span>
        <StatusPill variant={shareChipVariant(38)}>38% ABX share</StatusPill>
        <RefreshPill/>
      </div>
      <div style={{fontSize:11,color:"#64748B",marginBottom:12}}>Broad-spectrum antibiotic prescribing audited per doctor chamber (AWaRe watch list) — regional stewardship compliance and detailing focus at a glance.</div>
      <div style={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:8,marginBottom:12}}>
        <MiniKPI label="Chambers audited" value="28"/>
        <MiniKPI label="Chambers prescribing ABX" value="19"/>
        <MiniKPI label="ABX items (30d)" value="142"/>
        <MiniKPI label="Broad-spectrum ★" value="11"/>
      </div>
      {chambers.map((c,i)=>(
        <div key={i} style={{border:"1px solid #F1F5F9",borderRadius:12,padding:12,marginBottom:8}}>
          <div style={{marginBottom:4}}>
            <div style={{fontSize:13,fontWeight:500,color:"#0F172A"}}>{c.doctor}</div>
            <div style={{fontSize:10,color:"#64748B"}}>{c.spec} · {c.district} · {c.chamber}</div>
          </div>
          <div style={{display:"flex",gap:12,flexWrap:"wrap",marginBottom:6}}>
            <span style={{fontSize:11,color:"#475569"}}>{c.rxAudited} Rx audited</span>
            <span style={{fontSize:11,fontWeight:700,color:c.abxItems>=4?"#DC2626":c.abxItems>0?"#D97706":"#94A3B8"}}>{c.abxItems}</span>
            <span style={{fontSize:11,color:"#94A3B8"}}>/ {c.rxAudited*4} items</span>
            <span style={{fontSize:11,color:"#DC2626",fontWeight:700}}>★ {c.broad}</span>
          </div>
          <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:6}}>
            <div style={{flex:1,height:8,background:"#E2E8F0",borderRadius:9999,overflow:"hidden"}}>
              <div style={{height:"100%",width:`${c.sharePct}%`,background:c.sharePct>=40?"#DC2626":c.sharePct>=20?"#D97706":"#059669",borderRadius:9999}}/>
            </div>
            <span style={{fontSize:9,color:"#94A3B8",flexShrink:0}}>{c.sharePct}%</span>
          </div>
          <div style={{fontSize:10,color:"#64748B"}}>Brands: {c.brands}</div>
        </div>
      ))}
      {chambers.length===0 && <EmptyState icon="🦠" text="No stewardship data yet — scan prescriptions to audit ABX trends."/>}
    </Card>
  );
}

function TeamScreen() {
  return (
    <div>
      {/* Dark hero */}
      <DarkHero style={{marginBottom:16}}>
        <div style={{fontSize:11,color:"rgba(255,255,255,0.70)",textTransform:"uppercase",letterSpacing:"0.1em",marginBottom:6}}>Multi-tenant org hierarchy</div>
        <div style={{fontSize:20,fontWeight:700,color:"#fff",letterSpacing:"-0.02em",marginBottom:6}}>Territory Manager / RSM Command</div>
        <div style={{fontSize:13,color:"rgba(255,255,255,0.80)",marginBottom:12}}>Aggregated prescription audits across a 50+ MPO field team, with competitive Share of Voice.</div>
        <button style={{display:"inline-flex",alignItems:"center",gap:6,padding:"8px 14px",borderRadius:12,background:"#fff",color:"#0F172A",fontSize:13,fontWeight:700,border:"none",cursor:"pointer"}}>
          <span style={{color:"#DC2626"}}>📑</span> Generate DGDA / Compliance Audit PDF
        </button>
        <div style={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:8,marginTop:16}}>
          <MiniKPI label="Team size" value="52" dark/>
          <MiniKPI label="Prescriptions" value="2,841" dark/>
          <MiniKPI label="Own items" value="1,142" dark/>
          <MiniKPI label="Team SoV" value="40%" dark/>
        </div>
      </DarkHero>
      <TeamMap/>
      <TeamTiers/>
      <TeamLeaderboard/>
      <TeamTargets/>
      <TeamOffTerritory/>
      <TeamStewardship/>
    </div>
  );
}

// ═══════════════════════════════════════════════════════════
// SETTINGS SCREEN
// ═══════════════════════════════════════════════════════════
function SettingsScreen({onHelp}:{onHelp:()=>void}) {
  return (
    <div>
      <Card style={{padding:24,marginBottom:16}}>
        <div style={{fontSize:14,fontWeight:700,color:"#0F172A",marginBottom:4}}>Enterprise Settings & Officer Profile</div>
        <div style={{fontSize:11,color:"#64748B",marginBottom:20}}>Onboard your company, identity card and monthly brand targets. Vision-model hits on those brands become KPI progress automatically.</div>

        {/* Company autocomplete */}
        <div style={{marginBottom:16}}>
          <div style={{fontSize:10,fontWeight:700,color:"#64748B",textTransform:"uppercase",letterSpacing:"0.08em",marginBottom:6}}>Pharmaceutical Company</div>
          <input placeholder="Search Square, Beximco, Incepta..." style={{width:"100%",height:44,borderRadius:12,border:"1px solid #E2E8F0",padding:"0 12px",fontSize:13,fontFamily:"Inter,sans-serif",outline:"none",boxSizing:"border-box"}}/>
          <div style={{fontSize:11,color:"#64748B",marginTop:4}}>Selected: Square Pharmaceuticals PLC</div>
          {/* Dropdown */}
          <div style={{background:"#fff",borderRadius:12,border:"1px solid #E2E8F0",boxShadow:"0 8px 24px rgba(0,0,0,0.08)",maxHeight:180,overflow:"auto",marginTop:4}}>
            {["Square Pharmaceuticals PLC","Incepta Pharmaceuticals Ltd.","Beximco Pharmaceuticals Ltd.","Renata Limited","ACI Limited"].map((c,i)=>(
              <div key={i} style={{display:"flex",alignItems:"center",gap:8,padding:"10px 12px",borderBottom:i<4?"1px solid #F1F5F9":"none",cursor:"pointer"}}>
                <CompanyBadge name={c} size={20}/>
                <span style={{fontSize:12,color:"#0F172A"}}>{c}</span>
              </div>
            ))}
          </div>
        </div>

        {/* Profile fields */}
        <div style={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:12,marginBottom:16}}>
          {[
            {label:"Employee ID",placeholder:"MR001"},
            {label:"Full Name",placeholder:"Your name"},
            {label:"Division",placeholder:"Dhaka"},
            {label:"Designated Territory / Zone",placeholder:"Dhaka South, Chittagong Metro..."},
            {label:"Assigned Product Portfolio",placeholder:"Cardiology, Gastroenterology"},
          ].map(f=>(
            <div key={f.label} style={{gridColumn:f.label.length>15?"1 / -1":undefined}}>
              <div style={{fontSize:10,fontWeight:700,color:"#64748B",textTransform:"uppercase",letterSpacing:"0.08em",marginBottom:4}}>{f.label}</div>
              <input placeholder={f.placeholder} style={{width:"100%",height:40,borderRadius:12,border:"1px solid #E2E8F0",padding:"0 12px",fontSize:12,fontFamily:"Inter,sans-serif",outline:"none",boxSizing:"border-box"}}/>
            </div>
          ))}
          <div>
            <div style={{fontSize:10,fontWeight:700,color:"#64748B",textTransform:"uppercase",letterSpacing:"0.08em",marginBottom:4}}>Role</div>
            <select style={{width:"100%",height:40,borderRadius:12,border:"1px solid #E2E8F0",padding:"0 12px",fontSize:12,fontFamily:"Inter,sans-serif",background:"#fff",outline:"none"}}>
              <option>MPO</option><option>RSO</option><option>RSM</option><option>Territory Manager</option><option>Product Manager</option>
            </select>
          </div>
        </div>

        {/* Monthly brand targets */}
        <div style={{marginBottom:16}}>
          <div style={{display:"flex",alignItems:"center",justifyContent:"space-between",marginBottom:8}}>
            <div style={{fontSize:10,fontWeight:700,color:"#64748B",textTransform:"uppercase",letterSpacing:"0.08em"}}>Monthly Brand Targets</div>
            <button style={{fontSize:11,fontWeight:600,color:"#2563EB",background:"none",border:"none",cursor:"pointer"}}>＋ Add brand</button>
          </div>
          {[{brand:"Napa",target:200,actual:128},{brand:"Seclo",target:150,actual:96}].map(b=>(
            <div key={b.brand} style={{display:"flex",alignItems:"center",gap:10,marginBottom:10}}>
              <input defaultValue={b.brand} style={{flex:2,height:36,borderRadius:10,border:"1px solid #E2E8F0",padding:"0 10px",fontSize:12,fontFamily:"Inter,sans-serif",outline:"none"}}/>
              <input type="number" defaultValue={b.target} style={{flex:1,height:36,borderRadius:10,border:"1px solid #E2E8F0",padding:"0 8px",fontSize:12,fontFamily:"Inter,sans-serif",outline:"none"}}/>
              <button style={{width:28,height:28,borderRadius:6,border:"1px solid #E2E8F0",background:"#fff",cursor:"pointer",color:"#94A3B8",fontSize:12}}>✕</button>
            </div>
          ))}
          {/* Progress */}
          {[{brand:"Napa",target:200,actual:128},{brand:"Seclo",target:150,actual:96}].map(b=>(
            <div key={b.brand} style={{marginBottom:10}}>
              <div style={{display:"flex",justifyContent:"space-between",marginBottom:4}}>
                <span style={{fontSize:11,color:"#0F172A",fontWeight:600}}>{b.brand}</span>
                <span style={{fontSize:11,color:"#64748B"}}>{b.actual} / {b.target}</span>
              </div>
              <ProgressBar pct={Math.round(b.actual/b.target*100)}/>
              <div style={{fontSize:10,color:"#64748B",marginTop:2}}>{Math.round(b.actual/b.target*100)}%</div>
            </div>
          ))}
        </div>

        <PrimaryBtn icon="💾" style={{width:"100%",justifyContent:"center"}}>Save officer card</PrimaryBtn>
      </Card>

      {/* Offline note */}
      <Card style={{marginBottom:16}}>
        <div style={{display:"flex",gap:8,alignItems:"flex-start"}}>
          <span style={{color:"#059669",fontSize:14}}>📶</span>
          <div style={{fontSize:11,color:"#475569",lineHeight:1.5}}>Offline-first: scans cache on-device when the rural network drops, then sync on reconnect.</div>
        </div>
      </Card>

      {/* Help & Guide entry */}
      <button onClick={onHelp} style={{width:"100%",display:"flex",alignItems:"center",gap:12,padding:"14px 16px",background:"#fff",border:"1px solid #E2E8F0",borderRadius:12,cursor:"pointer",marginBottom:16}}>
        <span style={{fontSize:16}}>📖</span>
        <div style={{flex:1,textAlign:"left"}}>
          <div style={{fontSize:13,fontWeight:600,color:"#0F172A"}}>Help & Guide</div>
          <div style={{fontSize:11,color:"#64748B"}}>Scan guide, error escalation, BMDC reference</div>
        </div>
        <span style={{color:"#CBD5E1",fontSize:16}}>›</span>
      </button>
    </div>
  );
}

// ═══════════════════════════════════════════════════════════
// HELP & GUIDE SCREEN (Frame 37)
// ═══════════════════════════════════════════════════════════
function HelpScreen({onBack}:{onBack:()=>void}) {
  const steps = [
    {title:"1. Surface","body":"Lay the Rx flat. Avoid folded pads and wrinkled thermal paper."},
    {title:"2. Align","body":"All four corners visible. Portrait for Bengali pads, landscape for hospital sheets."},
    {title:"3. Light","body":"Even daylight. No flash glare on laminated pads. Shadow-free medicine block."},
    {title:"4. Cursive","body":"Move closer to the Rx lines. Use contrast toggle after capture for faint ink."},
    {title:"5. Capture","body":"Tap Open Camera (not Choose File) on phones. Hold still 1s. Review confidence badges."},
  ];
  return (
    <div>
      <Card style={{marginBottom:16,padding:24}}>
        <div style={{fontSize:14,fontWeight:700,color:"#0F172A",marginBottom:4}}>Interactive Scan Guide</div>
        <div style={{fontSize:11,color:"#64748B",marginBottom:16}}>Optimal camera alignment, lighting, and cursive handwriting for maximum vision-AI confidence.</div>
        {steps.map((s,i)=>(
          <div key={i} style={{background:"#F8FAFC",border:"1px solid #E2E8F0",borderRadius:12,padding:12,marginBottom:8}}>
            <div style={{fontSize:10,fontWeight:700,color:"#1D4ED8",marginBottom:4}}>{s.title}</div>
            <div style={{fontSize:11,color:"#475569",lineHeight:1.5}}>{s.body}</div>
          </div>
        ))}
        <PrimaryBtn style={{width:"100%",justifyContent:"center",marginTop:8}}>Try a scan</PrimaryBtn>
      </Card>

      <Card style={{marginBottom:16,padding:24}}>
        <div style={{fontSize:14,fontWeight:600,color:"#0F172A",marginBottom:4}}>Error Escalation System</div>
        <div style={{fontSize:11,color:"#64748B",marginBottom:12}}>Report a misidentified drug or a novel handwritten variation. It is queued for the backend training set — you can also flag from any medicine card after a scan.</div>
        <div style={{marginBottom:8}}>
          <div style={{fontSize:10,fontWeight:600,color:"#64748B",marginBottom:4}}>Detected brand</div>
          <input placeholder="e.g. Azithro" style={{width:"100%",height:40,borderRadius:12,border:"1px solid #E2E8F0",padding:"0 12px",fontSize:12,fontFamily:"Inter,sans-serif",outline:"none",boxSizing:"border-box"}}/>
        </div>
        <div style={{marginBottom:8}}>
          <div style={{fontSize:10,fontWeight:600,color:"#64748B",marginBottom:4}}>Correct brand / company</div>
          <input placeholder="e.g. Azithromycin (Incepta)" style={{width:"100%",height:40,borderRadius:12,border:"1px solid #E2E8F0",padding:"0 12px",fontSize:12,fontFamily:"Inter,sans-serif",outline:"none",boxSizing:"border-box"}}/>
        </div>
        <div style={{marginBottom:12}}>
          <div style={{fontSize:10,fontWeight:600,color:"#64748B",marginBottom:4}}>What did the handwriting look like?</div>
          <textarea placeholder="Describe the cursive letterforms, pen pressure, word breaks..." rows={3} style={{width:"100%",borderRadius:12,border:"1px solid #E2E8F0",padding:"10px 12px",fontSize:12,fontFamily:"Inter,sans-serif",outline:"none",resize:"none",boxSizing:"border-box"}}/>
        </div>
        <button style={{width:"100%",padding:"10px",borderRadius:12,background:"#D97706",color:"#fff",fontSize:13,fontWeight:600,border:"none",cursor:"pointer"}}>Queue for training</button>
      </Card>

      <Card style={{padding:24}}>
        <div style={{fontSize:14,fontWeight:600,color:"#0F172A",marginBottom:10}}>BMDC & DGDA Reference Manual</div>
        <ul style={{paddingLeft:16,margin:0}}>
          {[
            {bold:"BMDC number format:",text:"Prefix letter + 5 digits, e.g. A-12345. Accept hyphenated and un-hyphenated forms."},
            {bold:"Verification statuses:",text:"Green >85% auto-accept; 70–85% review; <70% manual flag required before save."},
            {bold:"Qualifications:",text:"MBBS, FCPS, MD, MS, MCPS — match against BMDC register."},
            {bold:"DGDA:",text:"Consult the DGDA essential medicine price list before quoting MRP to doctors."},
            {bold:"PII masking:",text:"Doctor name and BMDC number are masked in exported CSVs per BMDC compliance."},
          ].map((item,i)=>(
            <li key={i} style={{fontSize:12,color:"#475569",marginBottom:8,lineHeight:1.5}}>
              <strong>{item.bold}</strong> {item.text}
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}

// ═══════════════════════════════════════════════════════════
// EMPTY / ERROR / LOADING STATES (Frame 38)
// ═══════════════════════════════════════════════════════════
function EmptyStatesShowcase() {
  return (
    <div>
      <SectionLabel>Empty / Loading / Error / Offline States</SectionLabel>
      <EmptyState icon="📋" text="Scan your first 5 prescriptions to unlock generic-brand trends." cta="Start Scanning"/>
      <EmptyState icon="🥧" text="Competitor analysis locked — scan 10 prescriptions to see Square vs Incepta vs Your Company."/>
      <EmptyState icon="👨‍⚕️" text="Scan prescriptions to unlock doctor leaderboard with conversion rates."/>
      <EmptyState icon="💊" text="No scanned medicines yet. Scan a prescription to populate this feed."/>
      <EmptyState icon="📋" text="No history yet — scan your first prescription to unlock analytics"/>

      {/* Offline banner */}
      <div style={{background:"#FFFBEB",border:"1px solid #FDE68A",borderRadius:10,padding:"10px 14px",marginTop:12,fontSize:11,color:"#B45309"}}>
        ⚡ Offline — 3 scans queued. They will sync when you reconnect.
      </div>

      {/* Error states */}
      {["Could not load the catalogue slice.","News feed unavailable.","Job board unavailable.","Calendar unavailable.","Heatmap unavailable.","Tier data unavailable.","TRIPS watch list unavailable."].map(msg=>(
        <div key={msg} style={{background:"#FEE2E2",border:"1px solid #FECACA",borderRadius:8,padding:"8px 12px",marginTop:8,fontSize:12,color:"#DC2626"}}>
          ✕ {msg}
        </div>
      ))}

      {/* Toast strip */}
      <div style={{marginTop:16}}>
        <SectionLabel>Toast / Snackbar States</SectionLabel>
        <Toast variant="loading" message="Extracting with MedLenX VL…"/>
        <div style={{marginTop:8}}><Toast variant="success" message="Target attached — visits to Dr. A. K. M. Rahman will auto-log from scans"/></div>
        <div style={{marginTop:8}}><Toast variant="error" message="Could not attach target: HTTP 400"/></div>
        <div style={{marginTop:8}}><Toast variant="info" message="Off-territory audits refreshed"/></div>
        <div style={{marginTop:8,background:"#0F172A",borderRadius:8,padding:"12px 16px",fontSize:14,color:"#fff"}}>Pitch script copied</div>
      </div>
    </div>
  );
}

// ═══════════════════════════════════════════════════════════
// SPLASH SCREEN (Frame 00)
// ═══════════════════════════════════════════════════════════
function SplashScreen({onDone}:{onDone:()=>void}) {
  useEffect(()=>{ const t = setTimeout(onDone, 2500); return ()=>clearTimeout(t); },[onDone]);
  return (
    <div style={{position:"fixed",inset:0,background:"#F8FAFC",display:"flex",flexDirection:"column",alignItems:"center",justifyContent:"center",zIndex:100}}>
      <div style={{display:"flex",flexDirection:"column",alignItems:"center",gap:16}}>
        <div style={{width:72,height:72,borderRadius:16,background:"linear-gradient(135deg,#0F172A,#1E40AF)",display:"flex",alignItems:"center",justifyContent:"center",fontSize:32,boxShadow:"0 12px 32px rgba(15,23,42,0.25)"}}>🔬</div>
        <div style={{textAlign:"center"}}>
          <div style={{fontSize:28,fontWeight:700,color:"#0F172A",letterSpacing:"-0.03em",lineHeight:1}}>MedLenX Lab</div>
          <div style={{fontSize:13,color:"#64748B",marginTop:6}}>Prescription Intelligence</div>
        </div>
      </div>
      <div style={{position:"absolute",bottom:80,left:24,right:24}}>
        <div style={{height:4,background:"#E2E8F0",borderRadius:9999,overflow:"hidden",marginBottom:8}}>
          <div style={{height:"100%",width:"40%",background:"#1D4ED8",borderRadius:9999,animation:"none"}}/>
        </div>
        <div style={{textAlign:"center",fontSize:11,color:"#64748B"}}>Connecting to MedLenX VL…</div>
      </div>
    </div>
  );
}

// ═══════════════════════════════════════════════════════════
// SCAN TAB — STATE MACHINE
// ═══════════════════════════════════════════════════════════
type ScanState = "empty"|"camera"|"scanning"|"verify-doctor"|"verify-medicine"|"verify-gps"|"saved"|"audit";

function ScanTab() {
  const [state, setState] = useState<ScanState>("empty");
  const [showPitchCard,setShowPitchCard] = useState(false);

  if (state==="camera") return <ScanCamera onCapture={()=>setState("scanning")} onBack={()=>setState("empty")}/>;
  if (state==="scanning") return (
    <ContentArea><ScanInProgress onDone={()=>setState("verify-doctor")}/></ContentArea>
  );

  return (
    <ContentArea>
      {state==="empty" && <ScanEmpty onCamera={()=>setState("camera")} onFile={()=>setState("scanning")}/>}
      {state==="verify-doctor" && <VerifyDoctor onNext={()=>setState("verify-medicine")} onBack={()=>setState("empty")}/>}
      {state==="verify-medicine" && <VerifyMedicines onNext={()=>setState("verify-gps")} onBack={()=>setState("verify-doctor")}/>}
      {state==="verify-gps" && <VerifyGPS onSave={()=>setState("saved")} onBack={()=>setState("verify-medicine")}/>}
      {state==="saved" && <ScanSaved onScanAnother={()=>setState("empty")} onAudit={()=>setState("audit")}/>}
      {state==="audit" && <>
        <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:16}}>
          <button onClick={()=>setState("saved")} style={{width:32,height:32,borderRadius:8,border:"1px solid #E2E8F0",background:"#fff",cursor:"pointer",fontSize:14}}>‹</button>
          <span style={{fontSize:14,fontWeight:600,color:"#0F172A"}}>Rx Audit Summary</span>
        </div>
        <RxAuditSummary onBack={()=>setState("saved")} onPitchCard={()=>setShowPitchCard(true)}/>
      </>}
      {showPitchCard && <DoctorPitchCard onClose={()=>setShowPitchCard(false)}/>}
    </ContentArea>
  );
}

// ═══════════════════════════════════════════════════════════
// MAIN APP
// ═══════════════════════════════════════════════════════════
export default function App() {
  const [showSplash, setShowSplash] = useState(true);
  const [activeTab, setActiveTab] = useState("scan");
  const [showSearch, setShowSearch] = useState(false);
  const [showHelp, setShowHelp] = useState(false);

  if (showSplash) return <SplashScreen onDone={()=>setShowSplash(false)}/>;

  return (
    <div style={{maxWidth:412,margin:"0 auto",position:"relative",minHeight:"100vh",background:"#F8FAFC",fontFamily:"Inter,sans-serif"}}>
      <AppBar showSearch={showSearch} setShowSearch={setShowSearch}/>
      {showSearch && <SearchOverlay onClose={()=>setShowSearch(false)}/>}

      {activeTab==="scan" && <ScanTab/>}

      {activeTab==="analytics" && (
        <ContentArea>
          <AnalyticsOverview/>
        </ContentArea>
      )}

      {activeTab==="hub" && (
        <ContentArea>
          <HubScreen/>
        </ContentArea>
      )}

      {activeTab==="team" && (
        <ContentArea>
          <TeamScreen/>
        </ContentArea>
      )}

      {activeTab==="settings" && (
        <ContentArea>
          {showHelp ? (
            <>
              <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:16}}>
                <button onClick={()=>setShowHelp(false)} style={{width:32,height:32,borderRadius:8,border:"1px solid #E2E8F0",background:"#fff",cursor:"pointer",fontSize:14}}>‹</button>
                <span style={{fontSize:14,fontWeight:600,color:"#0F172A"}}>Help & Guide</span>
              </div>
              <HelpScreen onBack={()=>setShowHelp(false)}/>
            </>
          ) : (
            <SettingsScreen onHelp={()=>setShowHelp(true)}/>
          )}
        </ContentArea>
      )}

      <BottomNav active={activeTab} onChange={setActiveTab}/>
    </div>
  );
}
