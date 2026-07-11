import { useEffect, useRef } from "react";
import { Link } from "react-router-dom";
import * as THREE from "three";
import { IconAdjustments, IconShieldCheck, IconRefresh } from "../ui";

const PALETTE = [0x7f77dd, 0x1d9e75, 0xd85a30, 0xd4537e, 0xef9f27];

function useHeroScene(canvasRef: React.RefObject<HTMLCanvasElement>) {
  useEffect(() => {
    const canvas = canvasRef.current;
    const container = canvas?.parentElement;
    if (!canvas || !container) return;

    let width = container.clientWidth;
    let height = container.clientHeight;

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(45, width / height, 0.1, 100);
    camera.position.set(0, 0, 9);

    const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.setSize(width, height);
    renderer.setClearColor(0x14122a, 1);

    scene.add(new THREE.AmbientLight(0x6a63b0, 0.7));
    const key = new THREE.DirectionalLight(0x9f97ff, 1.1);
    key.position.set(4, 5, 6);
    scene.add(key);
    const rim = new THREE.DirectionalLight(0x1d9e75, 0.8);
    rim.position.set(-5, -3, 2);
    scene.add(rim);

    const depth = 8.5;
    const fovRad = (camera.fov * Math.PI) / 180;
    const frustumHalfHeight = Math.tan(fovRad / 2) * depth;
    const frustumHalfWidth = frustumHalfHeight * camera.aspect;
    const sizeScale = THREE.MathUtils.clamp(frustumHalfHeight / 2.4, 0.85, 2.2);
    const clusterCenterX = frustumHalfWidth * 0.32;
    const spreadX = Math.min(frustumHalfWidth * 0.6, frustumHalfWidth - clusterCenterX - 0.5);
    const spreadY = frustumHalfHeight * 0.55;

    type ShapeData = { baseY: number; speed: number; phase: number; spin: number };
    const shapes: THREE.Mesh[] = [];
    const shapeData = new Map<THREE.Mesh, ShapeData>();
    const geoms: THREE.BufferGeometry[] = [
      new THREE.IcosahedronGeometry(1, 0),
      new THREE.OctahedronGeometry(0.9, 0),
      new THREE.TorusGeometry(0.8, 0.28, 12, 32),
      new THREE.IcosahedronGeometry(0.7, 0),
      new THREE.OctahedronGeometry(1.1, 0),
      new THREE.TorusGeometry(0.6, 0.2, 12, 32),
    ];
    const mats: THREE.Material[] = [];
    geoms.forEach((geom, i) => {
      const mat = new THREE.MeshStandardMaterial({ color: PALETTE[i % PALETTE.length], roughness: 0.35, metalness: 0.25 });
      mats.push(mat);
      const mesh = new THREE.Mesh(geom, mat);
      const angle = (i / geoms.length) * Math.PI * 2;
      const r = 0.65 + (i % 2) * 0.35;
      mesh.position.set(
        clusterCenterX + Math.cos(angle) * spreadX * r,
        Math.sin(angle * 1.3) * spreadY * r,
        Math.sin(angle) * 1.6 - 1.2,
      );
      mesh.scale.setScalar(sizeScale);
      shapeData.set(mesh, { baseY: mesh.position.y, speed: 0.6 + Math.random() * 0.6, phase: Math.random() * Math.PI * 2, spin: 0.15 + Math.random() * 0.25 });
      scene.add(mesh);
      shapes.push(mesh);
    });

    const centerGeo = new THREE.TorusKnotGeometry(0.55, 0.16, 100, 12);
    const centerMat = new THREE.MeshStandardMaterial({ color: 0xf1efe8, roughness: 0.25, metalness: 0.4 });
    const centerMesh = new THREE.Mesh(centerGeo, centerMat);
    centerMesh.position.set(clusterCenterX, -0.3, 0.6);
    centerMesh.scale.setScalar(sizeScale);
    scene.add(centerMesh);

    let mouseX = 0, mouseY = 0;
    function handleMouseMove(e: MouseEvent) {
      const rect = container!.getBoundingClientRect();
      mouseX = ((e.clientX - rect.left) / rect.width) * 2 - 1;
      mouseY = ((e.clientY - rect.top) / rect.height) * 2 - 1;
    }
    container.addEventListener("mousemove", handleMouseMove);

    function handleResize() {
      width = container!.clientWidth;
      height = container!.clientHeight;
      camera.aspect = width / height;
      camera.updateProjectionMatrix();
      renderer.setSize(width, height);
    }
    window.addEventListener("resize", handleResize);

    let disposed = false;
    let raf = 0;
    const clock = new THREE.Clock();
    function animate() {
      if (disposed) return;
      raf = requestAnimationFrame(animate);
      const t = clock.getElapsedTime();
      shapes.forEach((s) => {
        const d = shapeData.get(s)!;
        s.rotation.x += d.spin * 0.01;
        s.rotation.y += d.spin * 0.015;
        s.position.y = d.baseY + Math.sin(t * d.speed + d.phase) * 0.25;
      });
      centerMesh.rotation.x = t * 0.3;
      centerMesh.rotation.y = t * 0.4;

      camera.position.x += (mouseX * 1.2 - camera.position.x) * 0.04;
      camera.position.y += (-mouseY * 0.8 - camera.position.y) * 0.04;
      camera.lookAt(0, 0, 0);

      renderer.render(scene, camera);
    }
    animate();

    return () => {
      disposed = true;
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", handleResize);
      container.removeEventListener("mousemove", handleMouseMove);
      geoms.forEach((g) => g.dispose());
      centerGeo.dispose();
      mats.forEach((m) => m.dispose());
      centerMat.dispose();
      renderer.dispose();
    };
  }, [canvasRef]);
}

function FeatureCard({ icon: Icon, title, body }: {
  icon: (p: { className?: string }) => JSX.Element; title: string; body: string;
}) {
  return (
    <div className="rounded-xl border border-white/10 bg-white/5 p-4">
      <div className="mb-2.5 flex h-8 w-8 items-center justify-center rounded-full bg-indigo-400/20 text-indigo-300">
        <Icon className="h-4 w-4" />
      </div>
      <p className="mb-1 text-sm font-semibold text-slate-50">{title}</p>
      <p className="text-sm text-slate-400">{body}</p>
    </div>
  );
}

export default function Landing() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  useHeroScene(canvasRef);

  return (
    <div className="min-h-screen bg-[#14122a] text-slate-50">
      <div className="relative mx-auto h-[420px] max-w-5xl overflow-hidden px-4 sm:h-[460px] sm:px-6">
        <canvas ref={canvasRef} className="absolute inset-0 h-full w-full" />
        <div className="absolute inset-0 flex flex-col p-6 sm:p-10">
          <div className="flex items-center justify-between">
            <Link to="/" className="text-base font-semibold text-slate-50">Squarely</Link>
            <Link to="/login" className="rounded-lg border border-white/30 px-3.5 py-1.5 text-xs text-slate-50 hover:bg-white/10">
              Log in
            </Link>
          </div>
          <div className="flex max-w-sm flex-1 flex-col justify-center">
            <h1 className="mb-2.5 text-3xl font-semibold leading-tight text-slate-50 sm:text-4xl">
              Split expenses. <br /> Settle up for real.
            </h1>
            <p className="mb-5 text-sm leading-relaxed text-slate-300">
              Track, split, and close the loop with two-party confirmation — no more payment screenshots.
            </p>
            <Link to="/login" className="block rounded-lg bg-indigo-400 px-4 py-2.5 text-center text-sm font-medium text-indigo-950 hover:bg-indigo-300">
              Get started
            </Link>
          </div>
        </div>
      </div>

      <div id="features" className="mx-auto grid max-w-5xl gap-3 px-4 py-10 sm:grid-cols-3 sm:px-6">
        <FeatureCard icon={IconAdjustments} title="Split any way" body="Equal, exact amounts, percentages, or shares — pick what fits." />
        <FeatureCard icon={IconShieldCheck} title="Settle up for real" body="Two-party confirmation replaces payment screenshots." />
        <FeatureCard icon={IconRefresh} title="Track recurring bills" body="Rent, Wi-Fi, subscriptions — tracked automatically and ready to settle the moment they're due." />
      </div>
    </div>
  );
}
